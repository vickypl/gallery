package com.example.customgallery

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
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
import android.widget.ImageView
import android.widget.TextView
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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update

private const val PAGE_SIZE = 150
private const val THUMB_SIZE = 400

data class MediaItem(
    val id: Long,
    val contentUri: Uri,
    val isVideo: Boolean
)

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
            val queryArgs = Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
                putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sortOrder)
                putString(ContentResolver.QUERY_ARG_SQL_LIMIT, limit.toString())
            }

            val filesUri = MediaStore.Files.getContentUri("external")
            val cursor = runCatching {
                contentResolver.query(filesUri, projection, queryArgs, null)
            }.getOrElse {
                Log.w("MediaPagingSource", "Falling back to legacy query path", it)
                contentResolver.query(filesUri, projection, selection, args, "$sortOrder LIMIT $limit")
            }

            cursor?.use { c ->
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
        } catch (securityException: SecurityException) {
            Log.e("MediaPagingSource", "Media query security failure", securityException)
            LoadResult.Error(securityException)
        } catch (illegalArgumentException: IllegalArgumentException) {
            Log.e("MediaPagingSource", "Media query argument failure", illegalArgumentException)
            LoadResult.Error(illegalArgumentException)
        } catch (throwable: Throwable) {
            Log.e("MediaPagingSource", "Unexpected media query failure", throwable)
            LoadResult.Error(throwable)
        }
    }

    override fun getRefreshKey(state: PagingState<Long, MediaItem>): Long? = null
}

class GalleryViewModel(
    private val contentResolver: ContentResolver
) : ViewModel() {

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

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (hasAnyMediaPermission()) {
            pushPermissionsToViewModel()
        } else {
            Log.w("MainActivity", "Media permission denied; gallery will remain empty")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        Glide.get(this).setMemoryCategory(com.bumptech.glide.MemoryCategory.HIGH)

        val recycler = findViewById<RecyclerView>(R.id.mediaRecyclerView)
        val spanCount = calculateSpanCount()
        recycler.layoutManager = GridLayoutManager(this, spanCount)
        recycler.setHasFixedSize(true)
        recycler.setItemViewCacheSize(20)
        recycler.recycledViewPool.setMaxRecycledViews(0, 30)
        recycler.isDrawingCacheEnabled = false

        adapter = MediaPagingAdapter()
        recycler.adapter = adapter

        lifecycleScope.launchWhenStarted {
            viewModel.mediaFlow.collect { adapter.submitData(it) }
        }

        if (hasAnyMediaPermission()) {
            pushPermissionsToViewModel()
        } else {
            requestMediaPermissions()
        }
    }

    private fun calculateSpanCount(): Int {
        val densityDpi = resources.displayMetrics.densityDpi
        return when {
            densityDpi >= DisplayMetrics.DENSITY_XXHIGH -> 4
            else -> 3
        }
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
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
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

class MediaPagingAdapter : PagingDataAdapter<MediaItem, MediaViewHolder>(MEDIA_DIFF) {
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
}
