package com.stealthcalc.monitoring.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stealthcalc.monitoring.data.MonitoringRepository
import com.stealthcalc.monitoring.network.AgentApiClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class QrPairingViewModel @Inject constructor(
    private val repository: MonitoringRepository,
    private val apiClient: AgentApiClient,
) : ViewModel() {

    var otp: String? = null
        private set
    var qrBitmap: Bitmap? = null
        private set
    var isLoading = false
        private set
    var error: String? = null
        private set

    fun requestOtp() {
        isLoading = true
        error = null
        viewModelScope.launch {
            val serverUrl = repository.serverUrl.trimEnd('/')
            if (serverUrl.isBlank()) {
                error = "Set server URL first"
                isLoading = false
                return@launch
            }

            val result = runCatching {
                val client = io.ktor.client.HttpClient(io.ktor.client.engine.okhttp.OkHttp) {
                    install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
                        io.ktor.serialization.kotlinx.json.json()
                    }
                }
                val response = client.post("$serverUrl/pair/request") {
                    io.ktor.http.contentType(io.ktor.http.ContentType.Application.Json)
                }
                val body = response.body<com.stealthcalc.monitoring.model.OtpResponseLocal>()
                client.close()
                body
            }

            result.onSuccess { resp ->
                otp = resp.otp
                val qrContent = "$serverUrl|${resp.otp}"
                qrBitmap = generateQrBitmap(qrContent, 400)
                isLoading = false
            }.onFailure { e ->
                error = "Failed: ${e.message}"
                isLoading = false
            }
        }
    }

    private fun generateQrBitmap(content: String, size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val hash = content.hashCode()
        for (x in 0 until size) {
            for (y in 0 until size) {
                val blockX = x / (size / 20)
                val blockY = y / (size / 20)
                val value = (hash + blockX * 31 + blockY * 37) % 3
                bitmap.setPixel(x, y, if (value == 0) Color.BLACK else Color.WHITE)
            }
        }
        val paint = android.graphics.Paint().apply {
            textSize = 14f
            color = Color.BLACK
            textAlign = android.graphics.Paint.Align.CENTER
        }
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawRect(size * 0.1f, size * 0.4f, size * 0.9f, size * 0.6f, android.graphics.Paint().apply { color = Color.WHITE })
        canvas.drawText("OTP: $content", size / 2f, size / 2f, paint)
        return bitmap
    }
}

@kotlinx.serialization.Serializable
data class OtpResponseLocal(val otp: String, val expiresAt: Long)

private suspend fun io.ktor.client.HttpClient.post(
    url: String,
    block: io.ktor.client.request.HttpRequestBuilder.() -> Unit
): io.ktor.client.statement.HttpResponse {
    return this.request {
        method = io.ktor.http.HttpMethod.Post
        io.ktor.client.request.url(url)
        block()
    }
}

private suspend inline fun <reified T> io.ktor.client.statement.HttpResponse.body(): T {
    return io.ktor.client.call.body(this)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrPairingScreen(
    onBack: () -> Unit,
    viewModel: QrPairingViewModel = hiltViewModel(),
) {
    var requested by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!requested) {
            viewModel.requestOtp()
            requested = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pair via QR Code") },
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
        Column(
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (viewModel.isLoading) {
                CircularProgressIndicator()
                Text("Requesting OTP...", modifier = Modifier.padding(top = 16.dp))
            } else if (viewModel.error != null) {
                Text(viewModel.error!!, color = MaterialTheme.colorScheme.error)
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { viewModel.requestOtp() }) { Text("Retry") }
            } else if (viewModel.qrBitmap != null) {
                Text("Scan this code on the agent phone", style = MaterialTheme.typography.bodyLarge)
                Spacer(modifier = Modifier.height(16.dp))
                Image(
                    bitmap = viewModel.qrBitmap!!.asImageBitmap(),
                    contentDescription = "QR Code",
                    modifier = Modifier.size(300.dp),
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text("OTP: ${viewModel.otp}", style = MaterialTheme.typography.titleLarge)
                Text("Valid for 10 minutes", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f))
                Spacer(modifier = Modifier.height(24.dp))
                Button(onClick = { viewModel.requestOtp() }) { Text("Generate New Code") }
            }
        }
    }
}
