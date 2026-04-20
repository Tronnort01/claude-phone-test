package com.stealthcalc.vault.ui

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.PlayCircleFilled
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stealthcalc.vault.model.VaultFile
import com.stealthcalc.vault.model.VaultFileType
import com.stealthcalc.vault.model.VaultFolder
import com.stealthcalc.vault.model.VaultSortOrder
import com.stealthcalc.vault.service.FileEncryptionService
import com.stealthcalc.vault.viewmodel.VaultFilter
import com.stealthcalc.vault.viewmodel.VaultViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun VaultScreen(
    onBack: () -> Unit,
    onOpenFile: (VaultFile) -> Unit,
    onOpenCamera: () -> Unit = {},
    onPickPhotos: () -> Unit = {},
    onPickVideos: () -> Unit = {},
    viewModel: VaultViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val selectedIds by viewModel.selectedFileIds.collectAsStateWithLifecycle()
    val exportEvent by viewModel.exportEvent.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showNewFolder by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<VaultFile?>(null) }
    var bulkDeleteConfirm by remember { mutableStateOf(false) }
    var showImportOptions by remember { mutableStateOf(false) }
    var showSortPicker by remember { mutableStateOf(false) }
    val isSelectionMode = selectedIds.isNotEmpty()

    // Snackbar for export results.
    LaunchedEffect(exportEvent) {
        exportEvent?.let { evt ->
            val msg = when {
                evt.success == evt.total -> "Exported ${evt.success} file${if (evt.success == 1) "" else "s"} to your media library"
                evt.success == 0 -> "Export failed. See Settings → Diagnostics → Export crash log."
                else -> "Exported ${evt.success} of ${evt.total}. See diagnostics for failures."
            }
            snackbarHostState.showSnackbar(msg)
            viewModel.onExportEventHandled()
        }
    }

    // Document picker — kept as-is for non-gallery imports. Photos and
    // videos go through the in-app MediaStore picker (see onPickPhotos /
    // onPickVideos) to avoid handing off to Google Photos, which on
    // Pixel devices forces a cloud sign-in before returning any URIs.
    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.importFiles(uris, deleteOriginals = false)
        }
    }

    // Launcher for the MediaStore delete-confirmation dialog. When the VM
    // publishes an IntentSender (API 29 RecoverableSecurityException or API
    // 30+ createDeleteRequest), this launches the system dialog; on return,
    // we clear the pending request so it doesn't fire again on recomposition.
    val pendingDeleteRequest by viewModel.pendingDeleteRequest.collectAsStateWithLifecycle()
    val deleteConfirmLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) {
        viewModel.onDeleteRequestHandled()
    }
    LaunchedEffect(pendingDeleteRequest) {
        pendingDeleteRequest?.let { sender ->
            deleteConfirmLauncher.launch(IntentSenderRequest.Builder(sender).build())
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (isSelectionMode) {
                // Selection-mode toolbar: count + Export + Delete + Cancel.
                TopAppBar(
                    title = { Text("${selectedIds.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.clearSelection() }) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel selection")
                        }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.exportSelected() }) {
                            Icon(Icons.Default.FileDownload, contentDescription = "Export to library")
                        }
                        IconButton(onClick = { bulkDeleteConfirm = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        actionIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                )
            } else {
                TopAppBar(
                    title = {
                        if (state.isSearchActive) {
                            OutlinedTextField(
                                value = state.searchQuery,
                                onValueChange = viewModel::onSearchQueryChanged,
                                placeholder = { Text("Search vault...") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            Column {
                                Text(if (state.currentFolderId != null) "Folder" else "Secure Vault")
                                Text(
                                    "${state.fileCount} files • ${formatSize(state.totalSize)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            when {
                                state.isSearchActive -> viewModel.toggleSearch()
                                state.currentFolderId != null -> viewModel.navigateUp()
                                else -> onBack()
                            }
                        }) {
                            Icon(
                                if (state.isSearchActive) Icons.Default.Close
                                else Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back"
                            )
                        }
                    },
                    actions = {
                        if (!state.isSearchActive) {
                            IconButton(onClick = viewModel::toggleSearch) {
                                Icon(Icons.Default.Search, contentDescription = "Search")
                            }
                            IconButton(onClick = { showSortPicker = true }) {
                                Icon(Icons.Default.SortByAlpha, contentDescription = "Sort")
                            }
                            IconButton(onClick = viewModel::toggleGridView) {
                                Icon(
                                    if (state.isGridView) Icons.Default.ViewList else Icons.Default.GridView,
                                    contentDescription = "Toggle view"
                                )
                            }
                            IconButton(onClick = viewModel::regenerateThumbnails) {
                                Icon(Icons.Default.BrokenImage, contentDescription = "Regenerate thumbnails")
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground,
                    )
                )
            }
        },
        floatingActionButton = {
            // Hide FAB stack during selection mode so users focus on the
            // contextual export / delete actions in the toolbar.
            if (!isSelectionMode) {
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Camera FAB
                    FloatingActionButton(
                        onClick = onOpenCamera,
                        containerColor = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(Icons.Default.CameraAlt, contentDescription = "Secure Camera")
                    }
                    // Import FAB
                    FloatingActionButton(
                        onClick = { showImportOptions = true },
                        containerColor = MaterialTheme.colorScheme.primary
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Import")
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Filter chips
            if (!state.isSearchActive && state.currentFolderId == null) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(vertical = 8.dp)
                ) {
                    val filters = listOf(
                        VaultFilter.ALL to "All",
                        VaultFilter.PHOTOS to "Photos",
                        VaultFilter.VIDEOS to "Videos",
                        VaultFilter.DOCUMENTS to "Docs",
                        VaultFilter.FAVORITES to "Favorites",
                    )
                    items(filters) { (filter, label) ->
                        FilterChip(
                            selected = state.filter == filter,
                            onClick = { viewModel.setFilter(filter) },
                            label = { Text(label) }
                        )
                    }
                    item {
                        FilterChip(
                            selected = false,
                            onClick = { showNewFolder = true },
                            label = { Text("+") },
                            leadingIcon = { Icon(Icons.Default.CreateNewFolder, contentDescription = null) }
                        )
                    }
                }
            }

            // Importing indicator
            if (state.isImporting) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        state.importProgress,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            if (state.files.isEmpty() && state.folders.isEmpty() && !state.isImporting) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Vault is empty.\nTap + to import files.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(if (state.isGridView) 3 else 1),
                    contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    // Folders first
                    items(state.folders, key = { "folder_${it.id}" }) { folder ->
                        FolderCard(
                            folder = folder,
                            onClick = { viewModel.openFolder(folder.id) },
                            isGrid = state.isGridView,
                        )
                    }
                    // Then files
                    items(state.files, key = { it.id }) { file ->
                        val isSelected = file.id in selectedIds
                        VaultFileCard(
                            file = file,
                            isSelected = isSelected,
                            onClick = {
                                if (isSelectionMode) viewModel.toggleSelection(file.id)
                                else onOpenFile(file)
                            },
                            onLongClick = { viewModel.toggleSelection(file.id) },
                            onToggleFavorite = { viewModel.toggleFavorite(file.id) },
                            onDelete = { deleteTarget = file },
                            isGrid = state.isGridView,
                            encryptionService = viewModel.encryptionService,
                        )
                    }
                }
            }
        }
    }

    // Import options dialog
    if (showImportOptions) {
        AlertDialog(
            onDismissRequest = { showImportOptions = false },
            title = { Text("Import to Vault") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        showImportOptions = false
                        onPickPhotos()
                    }) { Text("Import Photos (removes from gallery)") }

                    TextButton(onClick = {
                        showImportOptions = false
                        onPickVideos()
                    }) { Text("Import Videos (removes from gallery)") }

                    TextButton(onClick = {
                        showImportOptions = false
                        filePickerLauncher.launch(arrayOf("*/*"))
                    }) { Text("Import Documents (keeps originals)") }
                }
            },
            confirmButton = {
                TextButton(onClick = { showImportOptions = false }) { Text("Cancel") }
            }
        )
    }

    // New folder dialog
    if (showNewFolder) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewFolder = false },
            title = { Text("New Folder") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Folder name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank()) {
                        viewModel.createFolder(name.trim())
                        showNewFolder = false
                    }
                }) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showNewFolder = false }) { Text("Cancel") }
            }
        )
    }

    // Sort picker dialog
    if (showSortPicker) {
        AlertDialog(
            onDismissRequest = { showSortPicker = false },
            title = { Text("Sort by") },
            text = {
                Column {
                    VaultSortOrder.entries.forEach { sort ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.setSortOrder(sort)
                                    showSortPicker = false
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            androidx.compose.material3.RadioButton(
                                selected = state.sortOrder == sort,
                                onClick = {
                                    viewModel.setSortOrder(sort)
                                    showSortPicker = false
                                }
                            )
                            Text(
                                sort.label,
                                modifier = Modifier.padding(start = 8.dp),
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSortPicker = false }) { Text("Cancel") }
            }
        )
    }

    // Delete confirmation
    deleteTarget?.let { file ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete permanently?") },
            text = { Text("\"${file.fileName}\" will be permanently deleted from the vault. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteFile(file)
                    deleteTarget = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            }
        )
    }

    // Bulk delete confirmation (selection-mode action).
    if (bulkDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { bulkDeleteConfirm = false },
            title = { Text("Delete ${selectedIds.size} file${if (selectedIds.size == 1) "" else "s"}?") },
            text = { Text("These will be permanently removed from the vault. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteSelected()
                    bulkDeleteConfirm = false
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { bulkDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VaultFileCard(
    file: VaultFile,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDelete: () -> Unit,
    isGrid: Boolean,
    encryptionService: FileEncryptionService,
) {
    val icon: ImageVector = when (file.fileType) {
        VaultFileType.PHOTO -> Icons.Default.CameraAlt
        VaultFileType.VIDEO -> Icons.Default.PlayCircleFilled
        VaultFileType.DOCUMENT -> Icons.Default.Description
        VaultFileType.AUDIO -> Icons.Default.AudioFile
        VaultFileType.OTHER -> Icons.Default.InsertDriveFile
    }

    val typeColor = when (file.fileType) {
        VaultFileType.PHOTO -> Color(0xFF4CAF50)
        VaultFileType.VIDEO -> Color(0xFF2196F3)
        VaultFileType.DOCUMENT -> Color(0xFFFF9800)
        VaultFileType.AUDIO -> Color(0xFF9C27B0)
        VaultFileType.OTHER -> Color(0xFF607D8B)
    }

    val cardModifier = if (isSelected) {
        Modifier.border(
            width = 3.dp,
            color = MaterialTheme.colorScheme.primary,
            shape = RoundedCornerShape(8.dp),
        )
    } else Modifier

    Card(
        modifier = cardModifier.combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick,
        ),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        if (isGrid) {
            // Grid view — square card with icon
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
            ) {
                // Decrypt the encrypted thumbnail off the main thread.
                // Keyed on id + thumbnailPath so list scrolls don't re-decrypt.
                val thumbBitmap by produceState<Bitmap?>(
                    initialValue = null,
                    key1 = file.id,
                    key2 = file.thumbnailPath,
                ) {
                    value = if (file.thumbnailPath == null) {
                        null
                    } else {
                        withContext(Dispatchers.IO) {
                            runCatching { encryptionService.decryptThumbnail(file) }.getOrNull()
                        }
                    }
                }

                val loadedThumb = thumbBitmap
                if (loadedThumb != null) {
                    Image(
                        bitmap = loadedThumb.asImageBitmap(),
                        contentDescription = file.fileName,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(typeColor.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(icon, contentDescription = null, tint = typeColor, modifier = Modifier.size(36.dp))
                    }
                }

                // Video duration badge
                if (file.fileType == VaultFileType.VIDEO && file.durationMs != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.Black.copy(alpha = 0.7f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            formatDuration(file.durationMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White
                        )
                    }
                }

                // Favorite indicator
                if (file.isFavorite) {
                    Icon(
                        Icons.Default.Favorite,
                        contentDescription = null,
                        tint = Color(0xFFFF4081),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                            .size(16.dp)
                    )
                }

                // Selection indicator (multi-select / export-mode visual).
                if (isSelected) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
                    )
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "Selected",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(4.dp)
                            .size(28.dp)
                    )
                }
            }
        } else {
            // List view
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(typeColor.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = null, tint = typeColor)
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        file.fileName,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        formatSize(file.fileSizeBytes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        if (file.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = "Favorite",
                        tint = if (file.isFavorite) Color(0xFFFF4081)
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f))
                }
            }
        }
    }
}

@Composable
private fun FolderCard(
    folder: VaultFolder,
    onClick: () -> Unit,
    isGrid: Boolean,
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        if (isGrid) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        folder.name,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Folder, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Text(folder.name, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

private fun formatSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    }
}

private fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%d:%02d".format(min, sec)
}
