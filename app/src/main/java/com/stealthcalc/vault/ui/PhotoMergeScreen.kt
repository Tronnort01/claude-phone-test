package com.stealthcalc.vault.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stealthcalc.vault.viewmodel.PhotoMergeViewModel

/**
 * Step 2 of the photo-merge flow: gesture-driven editor that composites the
 * overlay photo on top of the base photo. User can drag to move, pinch to
 * zoom, twist to rotate, and use a slider to set opacity. Tapping Save
 * commits the merge to a new VaultFile and pops back to the vault.
 *
 * The preview uses Compose's graphicsLayer for live transform — no Bitmap
 * is allocated per frame. The high-res composite only happens when the
 * user taps Save (see PhotoMergeViewModel.composeMerged).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoMergeScreen(
    onBack: () -> Unit,
    onSaved: () -> Unit,
    viewModel: PhotoMergeViewModel = hiltViewModel(),
) {
    val base by viewModel.baseBitmap.collectAsStateWithLifecycle()
    val overlay by viewModel.overlayBitmap.collectAsStateWithLifecycle()
    val isSaving by viewModel.isSaving.collectAsStateWithLifecycle()
    val saveResult by viewModel.saveResult.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    // Live transform state — pixels are in the editor's preview coordinate
    // system (size captured via onSizeChanged below). PhotoMergeViewModel
    // converts to result-image pixels at save time.
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var scale by remember { mutableFloatStateOf(0.5f) }
    var rotationDeg by remember { mutableFloatStateOf(0f) }
    var opacity by remember { mutableFloatStateOf(1f) }
    var previewSize by remember { mutableStateOf(0 to 0) }

    LaunchedEffect(saveResult) {
        when (val res = saveResult) {
            is PhotoMergeViewModel.SaveResult.Success -> {
                viewModel.consumeSaveResult()
                onSaved()
            }
            is PhotoMergeViewModel.SaveResult.Failure -> {
                snackbar.showSnackbar("Save failed: ${res.message}")
                viewModel.consumeSaveResult()
            }
            null -> Unit
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Merge photos") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            viewModel.mergeAndSave(
                                PhotoMergeViewModel.OverlayTransform(
                                    offsetX = offsetX,
                                    offsetY = offsetY,
                                    scale = scale,
                                    rotationDegrees = rotationDeg,
                                    opacity = opacity,
                                    previewWidthPx = previewSize.first,
                                    previewHeightPx = previewSize.second,
                                )
                            )
                        },
                        enabled = !isSaving && base != null && overlay != null && previewSize.first > 0,
                    ) {
                        Icon(Icons.Default.Save, contentDescription = "Save merged photo")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White,
                ),
            )
        },
        containerColor = Color.Black,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color.Black),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color.Black)
                    .onSizeChanged { previewSize = it.width to it.height }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, rotation ->
                            offsetX += pan.x
                            offsetY += pan.y
                            scale = (scale * zoom).coerceIn(0.05f, 8f)
                            rotationDeg += rotation
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                val baseBitmap = base
                val overlayBitmap = overlay
                if (baseBitmap == null || overlayBitmap == null) {
                    CircularProgressIndicator(color = Color.White)
                } else {
                    // Base, fit to the preview area.
                    Image(
                        bitmap = baseBitmap.asImageBitmap(),
                        contentDescription = "Base photo",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                    // Overlay, with the live transform applied via graphicsLayer.
                    Image(
                        bitmap = overlayBitmap.asImageBitmap(),
                        contentDescription = "Overlay photo",
                        modifier = Modifier
                            .graphicsLayer {
                                translationX = offsetX
                                translationY = offsetY
                                scaleX = scale
                                scaleY = scale
                                rotationZ = rotationDeg
                            }
                            .alpha(opacity),
                        contentScale = ContentScale.Fit,
                    )
                }
            }

            // Controls row.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("Opacity", color = Color.White, style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = opacity,
                    onValueChange = { opacity = it },
                    valueRange = 0f..1f,
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Drag to move • pinch to zoom • twist to rotate",
                        color = Color.White.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.weight(1f),
                    )
                    if (isSaving) {
                        Spacer(Modifier.width(8.dp))
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.height(16.dp).width(16.dp),
                        )
                    }
                }
            }
        }
    }
}
