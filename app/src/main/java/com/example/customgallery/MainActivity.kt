package com.example.customgallery

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Build
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.material.icons.filled.Share
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import coil.size.Scale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    private val pageSize = 180
    private var nextOffset = 0
    private var lastCanReadImages = false
    private var lastCanReadVideos = false

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
        loadNextPage(context = context)
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
        offset: Int
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

        val selection = mediaTypeFilters.joinToString(
            prefix = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (",
            postfix = ")"
        ) { "?" }
        val selectionArgs = mediaTypeFilters.map { it.toString() }.toTypedArray()

        val sortOrder = "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
        val queryArgs = Bundle().apply {
            putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs)
            putString(android.content.ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sortOrder)
            putString(android.content.ContentResolver.QUERY_ARG_SQL_LIMIT, "$limit OFFSET $offset")
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
                "$sortOrder LIMIT $limit OFFSET $offset"
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

        GalleryContent(
            state = state,
            onGridColumnChange = viewModel::setGridColumns,
            onToggleSelection = viewModel::toggleSelection,
            onLoadNextPage = { viewModel.loadNextPage(context) },
            onShareMedia = ::shareMediaItems,
            onDeleteMedia = ::requestDelete
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
private fun GalleryContent(
    state: GalleryUiState,
    onGridColumnChange: (Int) -> Unit,
    onToggleSelection: (String) -> Unit,
    onLoadNextPage: () -> Unit,
    onShareMedia: (List<MediaItem>) -> Unit,
    onDeleteMedia: (List<MediaItem>) -> Unit
) {
    val context = LocalContext.current
    var fullscreenIndex by remember { mutableStateOf<Int?>(null) }
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

    LaunchedEffect(state.mediaItems.size, fullscreenIndex) {
        val current = fullscreenIndex
        if (current != null && (current < 0 || current > state.mediaItems.lastIndex)) {
            fullscreenIndex = null
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
            items(state.mediaItems, key = { item -> item.stableId }) { item ->
                val selected = state.selectedMediaIds.contains(item.stableId)
                val thumbnailRequest = remember(item.uri) {
                    ImageRequest.Builder(context)
                        .data(item.uri)
                        .size(320)
                        .allowHardware(true)
                        .crossfade(false)
                        .scale(Scale.FILL)
                        .build()
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .combinedClickable(
                            onClick = {
                                fullscreenIndex = state.mediaItems.indexOf(item)
                            },
                            onLongClick = { onToggleSelection(item.stableId) }
                        )
                        .animateItemPlacement()
                ) {
                    AsyncImage(
                        model = thumbnailRequest,
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

    fullscreenIndex?.let { index ->
        FullscreenMediaViewer(
            mediaItems = state.mediaItems,
            initialIndex = index,
            onDismiss = { fullscreenIndex = null },
            onShare = { currentItem -> onShareMedia(listOf(currentItem)) },
            onDelete = { currentItem -> onDeleteMedia(listOf(currentItem)) }
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FullscreenMediaViewer(
    mediaItems: List<MediaItem>,
    initialIndex: Int,
    onDismiss: () -> Unit,
    onShare: (MediaItem) -> Unit,
    onDelete: (MediaItem) -> Unit
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
            context.imageLoader.enqueue(
                ImageRequest.Builder(context)
                    .data(mediaItem.uri)
                    .size(1_600)
                    .allowHardware(true)
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
                        .padding(horizontal = 24.dp, vertical = 24.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { onShare(currentItem) }) {
                        Icon(imageVector = Icons.Default.Share, contentDescription = "Share", tint = Color.White)
                    }
                    IconButton(onClick = { onDelete(currentItem) }) {
                        Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete", tint = Color.White)
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
