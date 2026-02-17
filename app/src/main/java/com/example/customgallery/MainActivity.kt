package com.example.customgallery

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingDataAdapter
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.cachedIn
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update

private const val PAGE_SIZE = 150
private const val THUMB_SIZE = 400

data class MediaItem(
    val id: Long,
    val contentUri: Uri,
    val isVideo: Boolean
) {
    val stableId: String = "${id}_${if (isVideo) "v" else "i"}"
}

class MediaPagingSource(
    private val contentResolver: ContentResolver,
    private val canReadImages: Boolean,
    private val canReadVideos: Boolean
) : PagingSource<Long, MediaItem>() {

    override suspend fun load(params: LoadParams<Long>): LoadResult<Long, MediaItem> {
        return try {
            if (!canReadImages && !canReadVideos) {
                return LoadResult.Page(data = emptyList(), prevKey = null, nextKey = null)
            }

            val startExclusive = params.key ?: Long.MAX_VALUE
            val limit = params.loadSize.coerceIn(100, 200)

            val projection = arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.MEDIA_TYPE
            )

            val mediaTypes = buildList {
                if (canReadImages) add(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE)
                if (canReadVideos) add(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO)
            }

            val selection = buildString {
                append("${MediaStore.Files.FileColumns._ID} < ?")
                append(" AND ${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (")
                append(mediaTypes.joinToString(",") { "?" })
                append(")")
            }

            val args = mutableListOf(startExclusive.toString()).apply {
                addAll(mediaTypes.map { it.toString() })
            }.toTypedArray()

            val sortOrder = "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC, ${MediaStore.Files.FileColumns._ID} DESC"

            val items = mutableListOf<MediaItem>()
            contentResolver.query(
                MediaStore.Files.getContentUri("external"),
                projection,
                selection,
                args,
                "$sortOrder LIMIT $limit"
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val typeCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val mediaType = c.getInt(typeCol)
                    val (baseUri, isVideo) = when (mediaType) {
                        MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI to false
                        MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI to true
                        else -> continue
                    }
                    items += MediaItem(id = id, contentUri = ContentUris.withAppendedId(baseUri, id), isVideo = isVideo)
                }
            }

            val nextKey = items.lastOrNull()?.id
            LoadResult.Page(data = items, prevKey = null, nextKey = nextKey)
        } catch (t: Throwable) {
            Log.e("MediaPagingSource", "Media query failure", t)
            LoadResult.Error(t)
        }
    }

    override fun getRefreshKey(state: PagingState<Long, MediaItem>): Long? = null
}

class GalleryViewModel(private val contentResolver: ContentResolver) : ViewModel() {
    private val permissionState = MutableStateFlow(false to false)

    val mediaFlow: Flow<PagingData<MediaItem>> = permissionState.flatMapLatest { (images, videos) ->
        Pager(
            config = PagingConfig(
                pageSize = PAGE_SIZE,
                initialLoadSize = PAGE_SIZE,
                prefetchDistance = 50,
                enablePlaceholders = false
            ),
            pagingSourceFactory = { MediaPagingSource(contentResolver, images, videos) }
        ).flow
    }.cachedIn(viewModelScope)

    fun setPermissions(canReadImages: Boolean, canReadVideos: Boolean) {
        permissionState.update { canReadImages to canReadVideos }
    }
}

class GalleryViewModelFactory(private val contentResolver: ContentResolver) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return GalleryViewModel(contentResolver) as T
    }
}

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<GalleryViewModel> { GalleryViewModelFactory(contentResolver) }

    private lateinit var adapter: MediaPagingAdapter
    private val selectedIds = linkedSetOf<String>()
    private val selectedItems = linkedMapOf<String, MediaItem>()

    private lateinit var selectedCountText: TextView
    private lateinit var shareButton: ImageButton
    private lateinit var deleteButton: ImageButton

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (hasAnyMediaPermission()) pushPermissionsToViewModel()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        selectedCountText = findViewById(R.id.selectedCountText)
        shareButton = findViewById(R.id.shareButton)
        deleteButton = findViewById(R.id.deleteButton)

        Glide.get(this).setMemoryCategory(com.bumptech.glide.MemoryCategory.HIGH)

        val recycler = findViewById<RecyclerView>(R.id.mediaRecyclerView)
        recycler.layoutManager = GridLayoutManager(this, calculateSpanCount())
        recycler.setHasFixedSize(true)
        recycler.setItemViewCacheSize(20)
        recycler.recycledViewPool.setMaxRecycledViews(0, 30)
        recycler.isDrawingCacheEnabled = false

        adapter = MediaPagingAdapter(
            onClick = { item -> onMediaClick(item) },
            onLongClick = { item -> toggleSelection(item) }
        )
        recycler.adapter = adapter

        shareButton.setOnClickListener { shareSelected() }
        deleteButton.setOnClickListener { deleteSelected() }
        renderSelectionUi()

        lifecycleScope.launchWhenStarted {
            viewModel.mediaFlow.collectLatest { adapter.submitData(it) }
        }

        if (hasAnyMediaPermission()) pushPermissionsToViewModel() else requestMediaPermissions()
    }

    private fun onMediaClick(item: MediaItem) {
        if (selectedIds.isNotEmpty()) {
            toggleSelection(item)
            return
        }
        val intent = Intent(this, PreviewActivity::class.java)
            .putExtra("uri", item.contentUri.toString())
            .putExtra("isVideo", item.isVideo)
        startActivity(intent)
    }

    private fun toggleSelection(item: MediaItem) {
        if (!selectedIds.add(item.stableId)) {
            selectedIds.remove(item.stableId)
            selectedItems.remove(item.stableId)
        } else {
            selectedItems[item.stableId] = item
        }
        adapter.setSelectedIds(selectedIds)
        renderSelectionUi()
    }

    private fun renderSelectionUi() {
        val hasSelection = selectedIds.isNotEmpty()
        selectedCountText.visibility = if (hasSelection) View.VISIBLE else View.GONE
        shareButton.visibility = if (hasSelection) View.VISIBLE else View.GONE
        deleteButton.visibility = if (hasSelection) View.VISIBLE else View.GONE
        selectedCountText.text = "${selectedIds.size} selected"
    }

    private fun shareSelected() {
        if (selectedItems.isEmpty()) return
        val uris = ArrayList(selectedItems.values.map { it.contentUri })
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share media"))
    }

    private fun deleteSelected() {
        if (selectedItems.isEmpty()) return
        var success = 0
        selectedItems.values.forEach { item ->
            runCatching {
                contentResolver.delete(item.contentUri, null, null)
            }.onSuccess {
                success += 1
            }.onFailure {
                Log.w("MainActivity", "Delete failed for ${item.contentUri}", it)
            }
        }
        Toast.makeText(this, "Deleted $success/${selectedItems.size}", Toast.LENGTH_SHORT).show()
        selectedIds.clear()
        selectedItems.clear()
        adapter.setSelectedIds(emptySet())
        renderSelectionUi()
        adapter.refresh()
    }

    private fun calculateSpanCount(): Int {
        val densityDpi = resources.displayMetrics.densityDpi
        return if (densityDpi >= DisplayMetrics.DENSITY_XXHIGH) 4 else 3
    }

    private fun requestMediaPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
                )
            } else {
                arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
            }
        } else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        permissionLauncher.launch(permissions)
    }

    private fun hasAnyMediaPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPermission(Manifest.permission.READ_MEDIA_IMAGES) ||
                hasPermission(Manifest.permission.READ_MEDIA_VIDEO) ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                    hasPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))
        } else {
            hasPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun pushPermissionsToViewModel() {
        val canReadImages: Boolean
        val canReadVideos: Boolean
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val selectedOnly = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                hasPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            canReadImages = hasPermission(Manifest.permission.READ_MEDIA_IMAGES) || selectedOnly
            canReadVideos = hasPermission(Manifest.permission.READ_MEDIA_VIDEO) || selectedOnly
        } else {
            val legacy = hasPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
            canReadImages = legacy
            canReadVideos = legacy
        }
        viewModel.setPermissions(canReadImages, canReadVideos)
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }
}

class MediaPagingAdapter(
    private val onClick: (MediaItem) -> Unit,
    private val onLongClick: (MediaItem) -> Unit
) : PagingDataAdapter<MediaItem, MediaViewHolder>(MEDIA_DIFF) {

    private var selectedIds: Set<String> = emptySet()

    fun setSelectedIds(ids: Set<String>) {
        selectedIds = ids
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MediaViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_media, parent, false)
        return MediaViewHolder(view)
    }

    override fun onBindViewHolder(holder: MediaViewHolder, position: Int) {
        val item = getItem(position) ?: return
        Glide.with(holder.itemView).clear(holder.imageView)
        Glide.with(holder.itemView)
            .asBitmap()
            .load(item.contentUri)
            .thumbnail(0.25f)
            .override(THUMB_SIZE, THUMB_SIZE)
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .skipMemoryCache(item.isVideo)
            .into(holder.imageView)

        holder.videoBadge.visibility = if (item.isVideo) View.VISIBLE else View.GONE
        holder.selectionBadge.visibility = if (selectedIds.contains(item.stableId)) View.VISIBLE else View.GONE

        holder.itemView.setOnClickListener { onClick(item) }
        holder.itemView.setOnLongClickListener {
            onLongClick(item)
            true
        }
    }

    override fun onViewRecycled(holder: MediaViewHolder) {
        Glide.with(holder.itemView).clear(holder.imageView)
        super.onViewRecycled(holder)
    }

    companion object {
        private val MEDIA_DIFF = object : DiffUtil.ItemCallback<MediaItem>() {
            override fun areItemsTheSame(oldItem: MediaItem, newItem: MediaItem): Boolean {
                return oldItem.id == newItem.id && oldItem.isVideo == newItem.isVideo
            }

            override fun areContentsTheSame(oldItem: MediaItem, newItem: MediaItem): Boolean {
                return oldItem == newItem
            }
        }
    }
}

class MediaViewHolder(view: View) : RecyclerView.ViewHolder(view) {
    val imageView: ImageView = view.findViewById(R.id.mediaImageView)
    val videoBadge: TextView = view.findViewById(R.id.videoBadge)
    val selectionBadge: TextView = view.findViewById(R.id.selectionBadge)
}
