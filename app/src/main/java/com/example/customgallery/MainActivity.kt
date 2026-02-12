package com.example.customgallery

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.MediaStore
import android.util.Log
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.IntentSenderRequest
import androidx.activity.viewModels
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Button
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ButtonDefaults
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Scale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class MediaType {
    PHOTO,
    VIDEO
}

data class MediaItem(
    val id: Long,
    val uri: Uri,
    val dateTakenOrModified: Long,
    val type: MediaType
) {
    val stableId: String = "${type.name}-$id"
}

data class GalleryUiState(
    val mediaItems: List<MediaItem> = emptyList(),
    val gridColumns: Int = 3,
    val selectedMediaIds: Set<String> = emptySet(),
    val hasMoreItems: Boolean = true,
    val isLoading: Boolean = false
)

data class AlbumsUiState(
    val albums: List<AlbumInfo> = emptyList(),
    val selectedAlbumId: Long? = null,
    val albumItems: List<MediaItem> = emptyList(),
    val hasMoreAlbumItems: Boolean = true,
    val isAlbumsLoading: Boolean = false,
    val isAlbumItemsLoading: Boolean = false
)

enum class GalleryScreenMode {
    GRID,
    ALBUMS,
    ALBUM_CONTENT
}

data class AlbumInfo(
    val bucketId: Long,
    val name: String,
    val itemCount: Int,
    val coverUri: Uri
)

data class MediaPermissionState(
    val canReadImages: Boolean,
    val canReadVideos: Boolean,
    val hasAnyMediaAccess: Boolean
)

class GalleryViewModel : ViewModel() {
    companion object {
        private const val TAG = "GalleryViewModel"
    }

    private val _uiState = MutableStateFlow(GalleryUiState())
    val uiState: StateFlow<GalleryUiState> = _uiState
    private val _albumsUiState = MutableStateFlow(AlbumsUiState())
    val albumsUiState: StateFlow<AlbumsUiState> = _albumsUiState

    private val pageSize = 180
    private val albumPageSize = 120
    private var nextOffset = 0
    private var albumNextOffset = 0
    private var lastCanReadImages = false
    private var lastCanReadVideos = false
    private var albumCache: List<AlbumInfo> = emptyList()

    fun refreshMedia(context: Context) {
        albumCache = emptyList()
        _albumsUiState.update { AlbumsUiState() }
        loadInitialMedia(context, lastCanReadImages, lastCanReadVideos)
    }

    fun loadInitialMedia(context: Context, canReadImages: Boolean, canReadVideos: Boolean) {
        lastCanReadImages = canReadImages
        lastCanReadVideos = canReadVideos
        nextOffset = 0
        _uiState.update {
            it.copy(
                mediaItems = emptyList(),
                hasMoreItems = true,
                isLoading = false,
                selectedMediaIds = emptySet()
            )
        }
        _albumsUiState.update { AlbumsUiState() }
        loadNextPage(context = context)
    }

    fun loadAlbums(context: Context, forceRefresh: Boolean = false) {
        val current = _albumsUiState.value
        if (current.isAlbumsLoading) return
        if (!forceRefresh && albumCache.isNotEmpty()) {
            _albumsUiState.update { it.copy(albums = albumCache) }
            return
        }

        _albumsUiState.update { it.copy(isAlbumsLoading = true) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                queryAlbumsInternal(
                    context = context,
                    canReadImages = lastCanReadImages,
                    canReadVideos = lastCanReadVideos
                )
            }.onSuccess { albums ->
                albumCache = albums
                _albumsUiState.update { it.copy(albums = albums, isAlbumsLoading = false) }
            }.onFailure { error ->
                Log.e(TAG, "Failed to load albums", error)
                _albumsUiState.update { it.copy(isAlbumsLoading = false) }
            }
        }
    }

    fun openAlbum(context: Context, bucketId: Long) {
        albumNextOffset = 0
        _albumsUiState.update {
            it.copy(
                selectedAlbumId = bucketId,
                albumItems = emptyList(),
                hasMoreAlbumItems = true,
                isAlbumItemsLoading = false
            )
        }
        loadNextAlbumPage(context)
    }

    fun loadNextAlbumPage(context: Context) {
        val current = _albumsUiState.value
        val selectedId = current.selectedAlbumId ?: return
        if (current.isAlbumItemsLoading || !current.hasMoreAlbumItems) return

        _albumsUiState.update { it.copy(isAlbumItemsLoading = true) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                queryMediaPage(
                    context = context,
                    canReadImages = lastCanReadImages,
                    canReadVideos = lastCanReadVideos,
                    limit = albumPageSize,
                    offset = albumNextOffset,
                    bucketId = selectedId
                )
            }.onSuccess { page ->
                albumNextOffset += page.size
                _albumsUiState.update {
                    it.copy(
                        albumItems = it.albumItems + page,
                        hasMoreAlbumItems = page.size == albumPageSize,
                        isAlbumItemsLoading = false
                    )
                }
            }.onFailure { error ->
                Log.e(TAG, "Failed to load album media page", error)
                _albumsUiState.update { it.copy(isAlbumItemsLoading = false, hasMoreAlbumItems = false) }
            }
        }
    }

    fun clearSelectedAlbum() {
        _albumsUiState.update {
            it.copy(
                selectedAlbumId = null,
                albumItems = emptyList(),
                hasMoreAlbumItems = true,
                isAlbumItemsLoading = false
            )
        }
    }

    fun loadNextPage(context: Context) {
        val current = _uiState.value
        if (current.isLoading || !current.hasMoreItems) {
            return
        }

        _uiState.update { it.copy(isLoading = true) }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val page = queryMediaPage(
                    context = context,
                    canReadImages = lastCanReadImages,
                    canReadVideos = lastCanReadVideos,
                    limit = pageSize,
                    offset = nextOffset
                )

                val pageHasMore = page.size == pageSize
                val newOffset = nextOffset + page.size
                nextOffset = newOffset

                _uiState.update {
                    it.copy(
                        mediaItems = it.mediaItems + page,
                        hasMoreItems = pageHasMore,
                        isLoading = false
                    )
                }
            } catch (cancellationException: CancellationException) {
                throw cancellationException
            } catch (exception: Exception) {
                Log.e(TAG, "Failed to load media page", exception)
                _uiState.update { it.copy(isLoading = false, hasMoreItems = false) }
            }
        }
    }

    fun setGridColumns(columns: Int) {
        _uiState.update { current -> current.copy(gridColumns = columns.coerceIn(2, 6)) }
    }

    fun toggleSelection(mediaId: String) {
        _uiState.update { current ->
            val mutableSet = current.selectedMediaIds.toMutableSet()
            if (!mutableSet.add(mediaId)) {
                mutableSet.remove(mediaId)
            }
            current.copy(selectedMediaIds = mutableSet)
        }
    }

    fun removeMediaByStableIds(stableIds: Set<String>) {
        if (stableIds.isEmpty()) return

        _uiState.update { current ->
            current.copy(
                mediaItems = current.mediaItems.filterNot { media -> stableIds.contains(media.stableId) },
                selectedMediaIds = current.selectedMediaIds - stableIds
            )
        }
    }

    private fun queryMediaPage(
        context: Context,
        canReadImages: Boolean,
        canReadVideos: Boolean,
        limit: Int,
        offset: Int,
        bucketId: Long? = null
    ): List<MediaItem> {
        if (!canReadImages && !canReadVideos) {
            return emptyList()
        }

        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.Files.FileColumns.DATE_ADDED,
            MediaStore.Files.FileColumns.DATE_MODIFIED
        )

        val mediaTypeFilters = buildList {
            if (canReadImages) {
                add(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE)
            }
            if (canReadVideos) {
                add(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO)
            }
        }

        val clauses = mutableListOf(
            mediaTypeFilters.joinToString(
                prefix = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (",
                postfix = ")"
            ) { "?" }
        )
        val selectionArgsList = mediaTypeFilters.map { it.toString() }.toMutableList()
        if (bucketId != null) {
            clauses += "${MediaStore.Files.FileColumns.BUCKET_ID} = ?"
            selectionArgsList += bucketId.toString()
        }
        val selection = clauses.joinToString(" AND ")
        val selectionArgs = selectionArgsList.toTypedArray()

        val sortOrder = "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
        val queryArgs = Bundle().apply {
            putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs)
            putString(android.content.ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sortOrder)
            putString(android.content.ContentResolver.QUERY_ARG_SQL_LIMIT, if (limit == Int.MAX_VALUE) null else "$limit OFFSET $offset")
        }

        val result = mutableListOf<MediaItem>()
        val filesUri = MediaStore.Files.getContentUri("external")

        val cursor = try {
            context.contentResolver.query(filesUri, projection, queryArgs, null)
        } catch (exception: Exception) {
            Log.w(TAG, "Falling back to legacy media query", exception)
            context.contentResolver.query(
                filesUri,
                projection,
                selection,
                selectionArgs,
                if (limit == Int.MAX_VALUE) sortOrder else "$sortOrder LIMIT $limit OFFSET $offset"
            )
        }

        cursor?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val mediaTypeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
            val addedColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)
            val modifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val mediaType = cursor.getInt(mediaTypeColumn)
                val dateAdded = cursor.getLong(addedColumn)
                val dateModified = cursor.getLong(modifiedColumn)

                val (contentUri, type) = when (mediaType) {
                    MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE -> {
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI to MediaType.PHOTO
                    }

                    MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> {
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI to MediaType.VIDEO
                    }

                    else -> continue
                }

                val uri = ContentUris.withAppendedId(contentUri, id)
                result.add(
                    MediaItem(
                        id = id,
                        uri = uri,
                        dateTakenOrModified = maxOf(dateAdded, dateModified),
                        type = type
                    )
                )
            }
        }

        return result
    }

    private fun queryAlbumsInternal(
        context: Context,
        canReadImages: Boolean,
        canReadVideos: Boolean
    ): List<AlbumInfo> {
        if (!canReadImages && !canReadVideos) return emptyList()

        val projection = arrayOf(
            MediaStore.Files.FileColumns.BUCKET_ID,
            MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME,
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.Files.FileColumns.DATE_MODIFIED
        )

        val mediaTypeFilters = buildList {
            if (canReadImages) add(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE)
            if (canReadVideos) add(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO)
        }

        val selection = mediaTypeFilters.joinToString(
            prefix = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (",
            postfix = ")"
        ) { "?" }

        val buckets = linkedMapOf<Long, Triple<String, Int, MediaItem?>>()
        context.contentResolver.query(
            MediaStore.Files.getContentUri("external"),
            projection,
            selection,
            mediaTypeFilters.map { it.toString() }.toTypedArray(),
            "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
        )?.use { cursor ->
            val bucketIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_ID)
            val bucketNameColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME)
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val mediaTypeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
            while (cursor.moveToNext()) {
                val bucketId = cursor.getLong(bucketIdColumn)
                if (bucketId == 0L) continue
                val bucketName = cursor.getString(bucketNameColumn) ?: "Unknown"
                val mediaId = cursor.getLong(idColumn)
                val mediaType = cursor.getInt(mediaTypeColumn)
                val (contentUri, type) = when (mediaType) {
                    MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI to MediaType.PHOTO
                    MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI to MediaType.VIDEO
                    else -> continue
                }
                val mediaItem = MediaItem(mediaId, ContentUris.withAppendedId(contentUri, mediaId), 0L, type)
                val current = buckets[bucketId]
                if (current == null) {
                    buckets[bucketId] = Triple(bucketName, 1, mediaItem)
                } else {
                    buckets[bucketId] = Triple(current.first, current.second + 1, current.third ?: mediaItem)
                }
            }
        }

        return buckets.mapNotNull { (bucketId, triple) ->
            val cover = triple.third ?: return@mapNotNull null
            AlbumInfo(bucketId = bucketId, name = triple.first, itemCount = triple.second, coverUri = cover.uri)
        }.sortedByDescending { it.itemCount }
    }

}

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<GalleryViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    GalleryScreen(viewModel = viewModel)
                }
            }
        }
    }
}

@Composable
private fun GalleryScreen(viewModel: GalleryViewModel) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val albumsState by viewModel.albumsUiState.collectAsStateWithLifecycle()
    var pendingDeleteIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        listOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        )
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    } else {
        listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun getPermissionState(): MediaPermissionState {
        val hasLegacyPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED

        val hasImagePermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_MEDIA_IMAGES
        ) == PackageManager.PERMISSION_GRANTED

        val hasVideoPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_MEDIA_VIDEO
        ) == PackageManager.PERMISSION_GRANTED

        val hasUserSelectedVisualPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            ) == PackageManager.PERMISSION_GRANTED

        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                val hasAnyMediaAccess = hasImagePermission || hasVideoPermission || hasUserSelectedVisualPermission
                MediaPermissionState(
                    canReadImages = hasImagePermission || hasUserSelectedVisualPermission,
                    canReadVideos = hasVideoPermission || hasUserSelectedVisualPermission,
                    hasAnyMediaAccess = hasAnyMediaAccess
                )
            }

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                val hasAnyMediaAccess = hasImagePermission || hasVideoPermission
                MediaPermissionState(
                    canReadImages = hasImagePermission,
                    canReadVideos = hasVideoPermission,
                    hasAnyMediaAccess = hasAnyMediaAccess
                )
            }

            else -> MediaPermissionState(
                canReadImages = hasLegacyPermission,
                canReadVideos = hasLegacyPermission,
                hasAnyMediaAccess = hasLegacyPermission
            )
        }
    }

    var permissionState by remember {
        mutableStateOf(getPermissionState())
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        permissionState = getPermissionState()
        if (permissionState.hasAnyMediaAccess) {
            viewModel.loadInitialMedia(
                context = context,
                canReadImages = permissionState.canReadImages,
                canReadVideos = permissionState.canReadVideos
            )
        }
    }

    val deleteLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.removeMediaByStableIds(pendingDeleteIds)
        }
        pendingDeleteIds = emptySet()
    }

    fun shareMediaItems(items: List<MediaItem>) {
        if (items.isEmpty()) return

        val uris = items.map { it.uri }
        val mimeType = when {
            items.all { it.type == MediaType.PHOTO } -> "image/*"
            items.all { it.type == MediaType.VIDEO } -> "video/*"
            else -> "*/*"
        }

        val shareIntent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uris.first())
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = mimeType
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }

        context.startActivity(Intent.createChooser(shareIntent, "Share media"))
    }

    fun requestDelete(items: List<MediaItem>) {
        if (items.isEmpty()) return

        val ids = items.map { it.stableId }.toSet()
        val uris = items.map { it.uri }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pendingDeleteIds = ids
            val intentSender = MediaStore.createDeleteRequest(context.contentResolver, uris).intentSender
            deleteLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            return
        }

        viewModel.viewModelScope.launch(Dispatchers.IO) {
            val deleted = mutableSetOf<String>()
            items.forEach { item ->
                runCatching {
                    context.contentResolver.delete(item.uri, null, null)
                }.onSuccess { rowsDeleted ->
                    if (rowsDeleted > 0) {
                        deleted.add(item.stableId)
                    }
                }
            }

            withContext(Dispatchers.Main) {
                viewModel.removeMediaByStableIds(deleted)
            }
        }
    }

    if (permissionState.hasAnyMediaAccess) {
        LaunchedEffect(permissionState) {
            viewModel.loadInitialMedia(
                context = context,
                canReadImages = permissionState.canReadImages,
                canReadVideos = permissionState.canReadVideos
            )
        }

        GalleryRootContent(
            state = state,
            albumsState = albumsState,
            onGridColumnChange = viewModel::setGridColumns,
            onToggleSelection = viewModel::toggleSelection,
            onLoadNextPage = { viewModel.loadNextPage(context) },
            onShareMedia = ::shareMediaItems,
            onDeleteMedia = ::requestDelete,
            onRefreshMedia = { viewModel.refreshMedia(context) },
            onLoadAlbums = { forceRefresh -> viewModel.loadAlbums(context, forceRefresh) },
            onOpenAlbum = { bucketId -> viewModel.openAlbum(context, bucketId) },
            onLoadNextAlbumPage = { viewModel.loadNextAlbumPage(context) },
            onClearSelectedAlbum = viewModel::clearSelectedAlbum
        )
    } else {
        PermissionView(onRequestPermission = { permissionLauncher.launch(permissions.toTypedArray()) })
    }
}

@Composable
private fun PermissionView(onRequestPermission: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "Media access is required to load photos and videos.")
        TextButton(onClick = onRequestPermission) {
            Text("Grant Access")
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GalleryGridContent(
    state: GalleryUiState,
    onGridColumnChange: (Int) -> Unit,
    onToggleSelection: (String) -> Unit,
    onLoadNextPage: () -> Unit,
    onShareMedia: (List<MediaItem>) -> Unit,
    onDeleteMedia: (List<MediaItem>) -> Unit,
    onOpenAlbums: () -> Unit,
    imageLoader: ImageLoader
) {
    val context = LocalContext.current
    var previewMediaId by remember { mutableStateOf<String?>(null) }
    val gridState = rememberLazyGridState()
    val shouldLoadNextPage by remember(state.mediaItems.size, state.hasMoreItems, state.isLoading) {
        derivedStateOf {
            val lastVisibleIndex = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            state.hasMoreItems && !state.isLoading && lastVisibleIndex >= state.mediaItems.lastIndex - 24
        }
    }
    val selectedMediaItems by remember(state.selectedMediaIds, state.mediaItems) {
        derivedStateOf {
            state.mediaItems.filter { item -> state.selectedMediaIds.contains(item.stableId) }
        }
    }

    LaunchedEffect(shouldLoadNextPage) {
        if (shouldLoadNextPage) {
            onLoadNextPage()
        }
    }

    LaunchedEffect(state.mediaItems, previewMediaId) {
        val previewId = previewMediaId ?: return@LaunchedEffect
        if (state.mediaItems.none { it.stableId == previewId }) {
            previewMediaId = null
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        GridControl(
            columns = state.gridColumns,
            onGridColumnChange = onGridColumnChange
        )

        if (selectedMediaItems.isNotEmpty()) {
            SelectedMediaActions(
                selectedCount = selectedMediaItems.size,
                onShareSelected = { onShareMedia(selectedMediaItems) },
                onDeleteSelected = { onDeleteMedia(selectedMediaItems) }
            )
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(state.gridColumns),
            state = gridState,
            modifier = Modifier
                .fillMaxSize()
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            itemsIndexed(state.mediaItems, key = { _, item -> item.stableId }) { _, item ->
                val selected = state.selectedMediaIds.contains(item.stableId)
                val thumbnailRequest = remember(item.uri) {
                    ImageRequest.Builder(context)
                        .data(item.uri)
                        .size(320)
                        .allowHardware(true)
                        .crossfade(false)
                        .scale(Scale.FILL)
                        .precision(Precision.INEXACT)
                        .memoryCachePolicy(CachePolicy.ENABLED)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .build()
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .combinedClickable(
                            onClick = {
                                previewMediaId = item.stableId
                            },
                            onLongClick = { onToggleSelection(item.stableId) }
                        )
                        .animateItemPlacement()
                ) {
                    AsyncImage(
                        model = thumbnailRequest,
                        imageLoader = imageLoader,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .size(140.dp),
                        contentScale = ContentScale.Crop
                    )

                    if (item.type == MediaType.VIDEO) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(8.dp)
                                .clip(CircleShape)
                                .background(Color(0xAA000000))
                                .padding(4.dp)
                        ) {
                            Text(
                                text = "▶",
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    TextButton(
                        onClick = { onToggleSelection(item.stableId) },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(2.dp)
                    ) {
                        Text(
                            text = if (selected) "✓" else "○",
                            color = if (selected) Color(0xFF1E88E5) else Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            if (state.isLoading) {
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }

            if (!state.isLoading && state.mediaItems.isEmpty()) {
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("No photos or videos found.")
                    }
                }
            }
        }
    }

    val previewIndex = remember(state.mediaItems, previewMediaId) {
        val previewId = previewMediaId ?: return@remember null
        state.mediaItems.indexOfFirst { it.stableId == previewId }.takeIf { it >= 0 }
    }

    previewIndex?.let { index ->
        FullscreenMediaViewer(
            mediaItems = state.mediaItems,
            initialIndex = index,
            onDismiss = { previewMediaId = null },
            onOpenAlbums = {
                previewMediaId = null
                onOpenAlbums()
            },
            onShare = { currentItem -> onShareMedia(listOf(currentItem)) },
            onDelete = { currentItem -> onDeleteMedia(listOf(currentItem)) },
            imageLoader = imageLoader
        )
    }
}

@Composable
private fun SelectedMediaActions(
    selectedCount: Int,
    onShareSelected: () -> Unit,
    onDeleteSelected: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Text(text = "$selectedCount selected")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(onClick = onShareSelected) {
                Icon(imageVector = Icons.Default.Share, contentDescription = "Share selected")
            }
            IconButton(onClick = onDeleteSelected) {
                Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete selected")
            }
        }
    }
}

@Composable
private fun GridControl(columns: Int, onGridColumnChange: (Int) -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Grid size")
            Text("$columns columns")
        }

        Slider(
            value = columns.toFloat(),
            onValueChange = { onGridColumnChange(it.toInt()) },
            valueRange = 2f..6f,
            steps = 3
        )
    }
}


@Composable
private fun rememberGalleryImageLoader(context: Context): ImageLoader {
    val appContext = context.applicationContext
    return remember(appContext) {
        val diskDirectory = File(appContext.cacheDir, "gallery_thumbs")
        val maxDiskSize = (StatFs(appContext.cacheDir.absolutePath).totalBytes * 0.05).toLong().coerceAtLeast(64L * 1024 * 1024)
        ImageLoader.Builder(appContext)
            .memoryCache {
                MemoryCache.Builder(appContext)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(diskDirectory)
                    .maxSizeBytes(maxDiskSize)
                    .build()
            }
            .crossfade(false)
            .build()
    }
}

@Composable
private fun GalleryRootContent(
    state: GalleryUiState,
    albumsState: AlbumsUiState,
    onGridColumnChange: (Int) -> Unit,
    onToggleSelection: (String) -> Unit,
    onLoadNextPage: () -> Unit,
    onShareMedia: (List<MediaItem>) -> Unit,
    onDeleteMedia: (List<MediaItem>) -> Unit,
    onRefreshMedia: () -> Unit,
    onLoadAlbums: (Boolean) -> Unit,
    onOpenAlbum: (Long) -> Unit,
    onLoadNextAlbumPage: () -> Unit,
    onClearSelectedAlbum: () -> Unit
) {
    val context = LocalContext.current
    var mode by remember { mutableStateOf(GalleryScreenMode.GRID) }
    val selectedAlbum = remember(albumsState.selectedAlbumId, albumsState.albums) {
        albumsState.albums.firstOrNull { it.bucketId == albumsState.selectedAlbumId }
    }
    val imageLoader = rememberGalleryImageLoader(context)

    DisposableEffect(albumsState.selectedAlbumId) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                onRefreshMedia()
                onLoadAlbums(true)
                albumsState.selectedAlbumId?.let { onOpenAlbum(it) }
            }
        }
        context.contentResolver.registerContentObserver(MediaStore.Files.getContentUri("external"), true, observer)
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }

    LaunchedEffect(mode) {
        if (mode == GalleryScreenMode.ALBUMS || mode == GalleryScreenMode.ALBUM_CONTENT) {
            onLoadAlbums(false)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        when (mode) {
            GalleryScreenMode.GRID -> GalleryGridContent(
                state = state,
                onGridColumnChange = onGridColumnChange,
                onToggleSelection = onToggleSelection,
                onLoadNextPage = onLoadNextPage,
                onShareMedia = onShareMedia,
                onDeleteMedia = onDeleteMedia,
                onOpenAlbums = { mode = GalleryScreenMode.ALBUMS },
                imageLoader = imageLoader
            )
            GalleryScreenMode.ALBUMS -> AlbumListContent(
                albums = albumsState.albums,
                isLoading = albumsState.isAlbumsLoading,
                imageLoader = imageLoader,
                onAlbumOpen = { album ->
                    onOpenAlbum(album.bucketId)
                    mode = GalleryScreenMode.ALBUM_CONTENT
                }
            )
            GalleryScreenMode.ALBUM_CONTENT -> AlbumMediaContent(
                title = selectedAlbum?.name.orEmpty(),
                items = albumsState.albumItems,
                hasMoreItems = albumsState.hasMoreAlbumItems,
                isLoading = albumsState.isAlbumItemsLoading,
                imageLoader = imageLoader,
                onBack = {
                    onClearSelectedAlbum()
                    mode = GalleryScreenMode.ALBUMS
                },
                onLoadNextPage = onLoadNextAlbumPage,
                onShareMedia = onShareMedia,
                onDeleteMedia = onDeleteMedia
            )
        }

        NavigationBar(modifier = Modifier.navigationBarsPadding()) {
            NavigationBarItem(selected = mode == GalleryScreenMode.GRID, onClick = { mode = GalleryScreenMode.GRID }, icon = { Icon(Icons.Default.Share, contentDescription = "Media") }, label = { Text("Media") })
            NavigationBarItem(selected = mode != GalleryScreenMode.GRID, onClick = { mode = GalleryScreenMode.ALBUMS }, icon = { Icon(Icons.Default.Folder, contentDescription = "Albums") }, label = { Text("Albums") })
            NavigationBarItem(selected = false, onClick = { }, enabled = false, icon = { Icon(Icons.Default.Delete, contentDescription = "Delete") }, label = { Text("Delete") })
        }
    }
}

@Composable
private fun AlbumListContent(
    albums: List<AlbumInfo>,
    isLoading: Boolean,
    imageLoader: ImageLoader,
    onAlbumOpen: (AlbumInfo) -> Unit
) {
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
        items(albums, key = { it.bucketId }) { album ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onAlbumOpen(album) }
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(album.coverUri)
                        .size(240)
                        .precision(Precision.INEXACT)
                        .memoryCachePolicy(CachePolicy.ENABLED)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .build(),
                    imageLoader = imageLoader,
                    contentDescription = album.name,
                    modifier = Modifier.size(64.dp),
                    contentScale = ContentScale.Crop
                )
                Column {
                    Text(album.name, fontWeight = FontWeight.SemiBold)
                    Text("${album.itemCount} items")
                }
            }
        }

        if (isLoading) {
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumMediaContent(
    title: String,
    items: List<MediaItem>,
    hasMoreItems: Boolean,
    isLoading: Boolean,
    imageLoader: ImageLoader,
    onBack: () -> Unit,
    onLoadNextPage: () -> Unit,
    onShareMedia: (List<MediaItem>) -> Unit,
    onDeleteMedia: (List<MediaItem>) -> Unit
) {
    var previewMediaId by remember { mutableStateOf<String?>(null) }
    val gridState = rememberLazyGridState()
    val shouldLoadNextPage by remember(items.size, hasMoreItems, isLoading) {
        derivedStateOf {
            val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            hasMoreItems && !isLoading && lastVisible >= items.lastIndex - 18
        }
    }

    LaunchedEffect(shouldLoadNextPage) {
        if (shouldLoadNextPage) {
            onLoadNextPage()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontWeight = FontWeight.Bold)
            TextButton(onClick = onBack) { Text("Back") }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            state = gridState,
            modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            itemsIndexed(items, key = { _, item -> item.stableId }) { _, item ->
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(item.uri)
                        .size(320)
                        .precision(Precision.INEXACT)
                        .memoryCachePolicy(CachePolicy.ENABLED)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .build(),
                    imageLoader = imageLoader,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .combinedClickable(
                            onClick = { previewMediaId = item.stableId },
                            onLongClick = {}
                        ),
                    contentScale = ContentScale.Crop
                )
            }

            if (isLoading) {
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }

    val previewIndex = remember(items, previewMediaId) {
        val previewId = previewMediaId ?: return@remember null
        items.indexOfFirst { it.stableId == previewId }.takeIf { it >= 0 }
    }

    previewIndex?.let { index ->
        FullscreenMediaViewer(
            mediaItems = items,
            initialIndex = index,
            onDismiss = { previewMediaId = null },
            onOpenAlbums = {
                previewMediaId = null
                onBack()
            },
            onShare = { currentItem -> onShareMedia(listOf(currentItem)) },
            onDelete = { currentItem -> onDeleteMedia(listOf(currentItem)) },
            imageLoader = imageLoader
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FullscreenMediaViewer(
    mediaItems: List<MediaItem>,
    initialIndex: Int,
    onDismiss: () -> Unit,
    onOpenAlbums: () -> Unit,
    onShare: (MediaItem) -> Unit,
    onDelete: (MediaItem) -> Unit,
    imageLoader: ImageLoader
) {
    if (mediaItems.isEmpty()) {
        return
    }

    val context = LocalContext.current
    val pagerState = rememberPagerState(initialPage = initialIndex, pageCount = { mediaItems.size })
    val currentItem = mediaItems.getOrNull(pagerState.currentPage)

    BackHandler(onBack = onDismiss)

    LaunchedEffect(pagerState.currentPage) {
        val preloadTargets = listOfNotNull(
            mediaItems.getOrNull(pagerState.currentPage - 1),
            mediaItems.getOrNull(pagerState.currentPage + 1)
        ).filter { it.type == MediaType.PHOTO }

        preloadTargets.forEach { mediaItem ->
            imageLoader.enqueue(
                ImageRequest.Builder(context)
                    .data(mediaItem.uri)
                    .size(1_600)
                    .allowHardware(true)
                    .precision(Precision.INEXACT)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .build()
            )
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.Black
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                val mediaItem = mediaItems[page]
                if (mediaItem.type == MediaType.PHOTO) {
                    val fullscreenRequest = remember(mediaItem.uri) {
                        ImageRequest.Builder(context)
                            .data(mediaItem.uri)
                            .allowHardware(true)
                            .crossfade(true)
                            .build()
                    }
                    AsyncImage(
                        model = fullscreenRequest,
                        imageLoader = imageLoader,
                        contentDescription = "Selected photo",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    FullscreenVideoPlayer(videoUri = mediaItem.uri)
                }
            }

            Text(
                text = "${pagerState.currentPage + 1}/${mediaItems.size}",
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 16.dp)
            )

            if (currentItem != null) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        IconButton(onClick = { onShare(currentItem) }) {
                            Icon(imageVector = Icons.Default.Share, contentDescription = "Share", tint = Color.White)
                        }
                    }
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Button(
                            onClick = onOpenAlbums,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0x66000000))
                        ) {
                            Text("Album", color = Color.White)
                        }
                    }
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                        IconButton(onClick = { onDelete(currentItem) }) {
                            Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete", tint = Color.White)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FullscreenVideoPlayer(videoUri: Uri) {
    AndroidView(
        modifier = Modifier
            .fillMaxSize(),
        factory = { context ->
            VideoView(context).apply {
                setVideoURI(videoUri)
                val mediaController = MediaController(context)
                mediaController.setAnchorView(this)
                setMediaController(mediaController)
                setOnPreparedListener { it.isLooping = true }
                start()
            }
        },
        update = { videoView ->
            videoView.setVideoURI(videoUri)
            videoView.start()
        }
    )

    DisposableEffect(videoUri) {
        onDispose {
            // VideoView resources are released when detached from window.
        }
    }
}
