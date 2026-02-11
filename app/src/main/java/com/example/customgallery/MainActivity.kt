package com.example.customgallery

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

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
    val selectedMediaIds: Set<String> = emptySet()
)

data class MediaPermissionState(
    val canReadImages: Boolean,
    val canReadVideos: Boolean,
    val hasAnyMediaAccess: Boolean
)

class GalleryViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(GalleryUiState())
    val uiState: StateFlow<GalleryUiState> = _uiState

    fun loadMedia(context: Context, canReadImages: Boolean, canReadVideos: Boolean) {
        val mediaItems = queryMedia(
            context = context,
            canReadImages = canReadImages,
            canReadVideos = canReadVideos
        )
        _uiState.update { current -> current.copy(mediaItems = mediaItems) }
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

    private fun queryMedia(context: Context, canReadImages: Boolean, canReadVideos: Boolean): List<MediaItem> {
        val photos = if (canReadImages) {
            queryMediaByType(
                context = context,
                collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                idColumnName = MediaStore.Images.Media._ID,
                dateAddedColumnName = MediaStore.Images.Media.DATE_ADDED,
                dateModifiedColumnName = MediaStore.Images.Media.DATE_MODIFIED,
                type = MediaType.PHOTO
            )
        } else {
            emptyList()
        }

        val videos = if (canReadVideos) {
            queryMediaByType(
                context = context,
                collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                idColumnName = MediaStore.Video.Media._ID,
                dateAddedColumnName = MediaStore.Video.Media.DATE_ADDED,
                dateModifiedColumnName = MediaStore.Video.Media.DATE_MODIFIED,
                type = MediaType.VIDEO
            )
        } else {
            emptyList()
        }

        return (photos + videos).sortedByDescending { it.dateTakenOrModified }
    }

    private fun queryMediaByType(
        context: Context,
        collection: Uri,
        idColumnName: String,
        dateAddedColumnName: String,
        dateModifiedColumnName: String,
        type: MediaType
    ): List<MediaItem> {
        val projection = arrayOf(
            idColumnName,
            dateAddedColumnName,
            dateModifiedColumnName
        )

        val orderBy = "$dateModifiedColumnName DESC"

        val result = mutableListOf<MediaItem>()
        context.contentResolver.query(
            collection,
            projection,
            null,
            null,
            orderBy
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(idColumnName)
            val addedColumn = cursor.getColumnIndexOrThrow(dateAddedColumnName)
            val modifiedColumn = cursor.getColumnIndexOrThrow(dateModifiedColumnName)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val dateAdded = cursor.getLong(addedColumn)
                val dateModified = cursor.getLong(modifiedColumn)
                val uri = ContentUris.withAppendedId(collection, id)
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
            viewModel.loadMedia(
                context = context,
                canReadImages = permissionState.canReadImages,
                canReadVideos = permissionState.canReadVideos
            )
        }
    }

    if (permissionState.hasAnyMediaAccess) {
        remember(permissionState) {
            viewModel.loadMedia(
                context = context,
                canReadImages = permissionState.canReadImages,
                canReadVideos = permissionState.canReadVideos
            )
            true
        }
        GalleryContent(
            state = state,
            onGridColumnChange = viewModel::setGridColumns,
            onToggleSelection = viewModel::toggleSelection
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
    onToggleSelection: (String) -> Unit
) {
    var fullscreenMedia by remember { mutableStateOf<MediaItem?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        GridControl(
            columns = state.gridColumns,
            onGridColumnChange = onGridColumnChange
        )

        LazyVerticalGrid(
            columns = GridCells.Fixed(state.gridColumns),
            modifier = Modifier
                .fillMaxSize()
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(state.mediaItems, key = { it.stableId }) { item ->
                val selected = state.selectedMediaIds.contains(item.stableId)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable {
                            fullscreenMedia = item
                        }
                        .animateItemPlacement()
                ) {
                    AsyncImage(
                        model = item.uri,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .size(140.dp)
                            .clickable { onToggleSelection(item.stableId) },
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

                    if (selected) {
                        Box(
                            modifier = Modifier
                                .padding(8.dp)
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(Color(0xAA1E88E5))
                                .align(Alignment.TopEnd),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("✓", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    fullscreenMedia?.let { item ->
        FullscreenMediaDialog(
            mediaItem = item,
            onDismiss = { fullscreenMedia = null }
        )
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
private fun FullscreenMediaDialog(mediaItem: MediaItem, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            if (mediaItem.type == MediaType.PHOTO) {
                AsyncImage(
                    model = mediaItem.uri,
                    contentDescription = "Selected photo",
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.Fit
                )
            } else {
                FullscreenVideoPlayer(videoUri = mediaItem.uri)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
        icon = {
            Text(
                text = if (mediaItem.type == MediaType.PHOTO) "📷" else "🎥"
            )
        }
    )
}

@Composable
private fun FullscreenVideoPlayer(videoUri: Uri) {
    AndroidView(
        modifier = Modifier
            .fillMaxWidth()
            .size(280.dp),
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
