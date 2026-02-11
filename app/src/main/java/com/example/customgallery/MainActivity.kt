package com.example.customgallery

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class PhotoItem(
    val id: Long,
    val uri: Uri,
    val dateTakenOrModified: Long
)

data class GalleryUiState(
    val photos: List<PhotoItem> = emptyList(),
    val gridColumns: Int = 3,
    val selectedPhotoIds: Set<Long> = emptySet()
)

class GalleryViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(GalleryUiState())
    val uiState: StateFlow<GalleryUiState> = _uiState

    fun loadPhotos(context: Context) {
        val photos = queryPhotos(context)
        _uiState.update { current -> current.copy(photos = photos) }
    }

    fun setGridColumns(columns: Int) {
        _uiState.update { current -> current.copy(gridColumns = columns.coerceIn(2, 6)) }
    }

    fun toggleSelection(photoId: Long) {
        _uiState.update { current ->
            val mutableSet = current.selectedPhotoIds.toMutableSet()
            if (!mutableSet.add(photoId)) {
                mutableSet.remove(photoId)
            }
            current.copy(selectedPhotoIds = mutableSet)
        }
    }

    private fun queryPhotos(context: Context): List<PhotoItem> {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DATE_MODIFIED
        )

        val orderBy = "${MediaStore.Images.Media.DATE_MODIFIED} DESC"

        val result = mutableListOf<PhotoItem>()
        context.contentResolver.query(
            collection,
            projection,
            null,
            null,
            orderBy
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val addedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val modifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val dateAdded = cursor.getLong(addedColumn)
                val dateModified = cursor.getLong(modifiedColumn)
                val uri = ContentUris.withAppendedId(collection, id)
                result.add(
                    PhotoItem(
                        id = id,
                        uri = uri,
                        dateTakenOrModified = maxOf(dateAdded, dateModified)
                    )
                )
            }
        }

        return result.sortedByDescending { it.dateTakenOrModified }
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

    val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (granted) {
            viewModel.loadPhotos(context)
        }
    }

    if (hasPermission) {
        remember(hasPermission) {
            viewModel.loadPhotos(context)
            true
        }
        GalleryContent(
            state = state,
            onGridColumnChange = viewModel::setGridColumns,
            onToggleSelection = viewModel::toggleSelection
        )
    } else {
        PermissionView(onRequestPermission = { permissionLauncher.launch(permission) })
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
        Text(text = "Photo access is required to load images.")
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
    onToggleSelection: (Long) -> Unit
) {
    var fullscreenPhoto by remember { mutableStateOf<PhotoItem?>(null) }

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
            items(state.photos, key = { it.id }) { photo ->
                val selected = state.selectedPhotoIds.contains(photo.id)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable {
                            fullscreenPhoto = photo
                        }
                        .animateItemPlacement()
                ) {
                    AsyncImage(
                        model = photo.uri,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .size(140.dp)
                            .clickable { onToggleSelection(photo.id) },
                        contentScale = ContentScale.Crop
                    )

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

    fullscreenPhoto?.let { item ->
        FullscreenPhotoDialog(
            photoUri = item.uri,
            onDismiss = { fullscreenPhoto = null }
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
private fun FullscreenPhotoDialog(photoUri: Uri, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            AsyncImage(
                model = photoUri,
                contentDescription = "Selected photo",
                modifier = Modifier.fillMaxWidth(),
                contentScale = ContentScale.Fit
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
        icon = {
            Icon(
                imageVector = androidx.compose.material.icons.Icons.Default.Photo,
                contentDescription = null
            )
        }
    )
}
