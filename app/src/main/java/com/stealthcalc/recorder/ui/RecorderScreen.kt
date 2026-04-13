package com.stealthcalc.recorder.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraFront
import androidx.compose.material.icons.filled.CameraRear
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stealthcalc.recorder.model.CameraFacing
import com.stealthcalc.recorder.model.RecordingType
import com.stealthcalc.recorder.viewmodel.RecorderViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecorderScreen(
    onBack: () -> Unit,
    onNavigateToRecordings: () -> Unit,
    viewModel: RecorderViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    AnimatedContent(
        targetState = state.showCoverScreen,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "cover_transition"
    ) { showCover ->
        if (showCover) {
            FakeSignInScreen(
                onExitToRecorder = { viewModel.exitCoverScreen() }
            )
        } else {
            RecorderControlPanel(
                state = state,
                onBack = onBack,
                onSelectMode = viewModel::selectMode,
                onSelectCamera = viewModel::selectCamera,
                onStartRecording = viewModel::startRecording,
                onStopRecording = viewModel::stopRecording,
                onShowCover = viewModel::enterCoverScreen,
                onNavigateToRecordings = onNavigateToRecordings,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecorderControlPanel(
    state: RecorderScreenState,
    onBack: () -> Unit,
    onSelectMode: (RecordingType) -> Unit,
    onSelectCamera: (CameraFacing) -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onShowCover: () -> Unit,
    onNavigateToRecordings: () -> Unit,
) {
    // Import the state class
    val isRecording = state.isRecording

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Recorder") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToRecordings) {
                        Text(
                            "Library",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Elapsed time display
            if (isRecording) {
                Text(
                    text = formatDuration(state.elapsedMs),
                    style = MaterialTheme.typography.displayLarge,
                    color = Color(0xFFEF5350),
                    fontSize = 48.sp
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.FiberManualRecord,
                        contentDescription = null,
                        tint = Color(0xFFEF5350),
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Recording ${if (state.selectedMode == RecordingType.VIDEO) "video" else "audio"}...",
                        color = Color(0xFFEF5350),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                Spacer(modifier = Modifier.height(48.dp))
            } else {
                // Mode selection
                Text(
                    "Recording Mode",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilterChip(
                        selected = state.selectedMode == RecordingType.AUDIO,
                        onClick = { onSelectMode(RecordingType.AUDIO) },
                        label = { Text("Audio") },
                        leadingIcon = {
                            Icon(Icons.Default.Mic, contentDescription = null)
                        }
                    )
                    FilterChip(
                        selected = state.selectedMode == RecordingType.VIDEO,
                        onClick = { onSelectMode(RecordingType.VIDEO) },
                        label = { Text("Video") },
                        leadingIcon = {
                            Icon(Icons.Default.Videocam, contentDescription = null)
                        }
                    )
                }

                // Camera selection (video only)
                if (state.selectedMode == RecordingType.VIDEO) {
                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        "Camera",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FilterChip(
                            selected = state.selectedCamera == CameraFacing.BACK,
                            onClick = { onSelectCamera(CameraFacing.BACK) },
                            label = { Text("Back") },
                            leadingIcon = {
                                Icon(Icons.Default.CameraRear, contentDescription = null)
                            }
                        )
                        FilterChip(
                            selected = state.selectedCamera == CameraFacing.FRONT,
                            onClick = { onSelectCamera(CameraFacing.FRONT) },
                            label = { Text("Front") },
                            leadingIcon = {
                                Icon(Icons.Default.CameraFront, contentDescription = null)
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(48.dp))
            }

            // Record / Stop button
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(if (isRecording) Color(0xFFEF5350) else MaterialTheme.colorScheme.primary)
                    .border(3.dp, Color.White.copy(alpha = 0.3f), CircleShape)
                    .clickable {
                        if (isRecording) onStopRecording() else onStartRecording()
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                    contentDescription = if (isRecording) "Stop" else "Record",
                    tint = Color.White,
                    modifier = Modifier.size(40.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = if (isRecording) "Tap to stop" else "Tap to start",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
            )

            // Show cover button (only when recording)
            if (isRecording) {
                Spacer(modifier = Modifier.height(32.dp))

                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable(onClick = onShowCover)
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Visibility,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Show fake sign-in screen",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    "Triple-tap top-left corner to return here",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                )
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

// Re-export for use in composable signature
private typealias RecorderScreenState = com.stealthcalc.recorder.viewmodel.RecorderScreenState
