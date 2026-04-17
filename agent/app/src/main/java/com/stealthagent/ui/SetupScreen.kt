package com.stealthagent.ui

import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.stealthagent.data.AgentRepository
import com.stealthagent.model.PairResponse
import com.stealthagent.network.AgentClient
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import com.stealthagent.model.PairRequest
import com.stealthagent.service.AgentForegroundService
import kotlinx.coroutines.launch

@Composable
fun SetupScreen(
    repository: AgentRepository,
    onComplete: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var serverUrl by remember { mutableStateOf(repository.serverUrl) }
    var directUrl by remember { mutableStateOf(repository.directUrl) }
    var deviceName by remember { mutableStateOf(repository.deviceName.ifBlank { Build.MODEL }) }
    var otp by remember { mutableStateOf("") }
    var status by remember { mutableStateOf(if (repository.isPaired) "Paired ✓" else "Not paired") }
    var isPairing by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Agent Setup", style = MaterialTheme.typography.headlineMedium)
        Text("Configure once, then this screen hides behind the calculator.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = serverUrl,
            onValueChange = { serverUrl = it },
            label = { Text("Server URL") },
            placeholder = { Text("http://home.tailnet:8080") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = directUrl,
            onValueChange = { directUrl = it },
            label = { Text("Direct URL (primary phone, optional)") },
            placeholder = { Text("http://192.168.1.x:8080") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = deviceName,
            onValueChange = { deviceName = it },
            label = { Text("Device Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = otp,
            onValueChange = { if (it.length <= 6 && it.all { c -> c.isDigit() }) otp = it },
            label = { Text("6-digit OTP from server") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Text(status, color = if (status.contains("✓")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f))

        Button(
            onClick = {
                repository.setServerUrl(serverUrl)
                repository.setDirectUrl(directUrl)
                repository.setDeviceName(deviceName)
                if (otp.length == 6) {
                    isPairing = true
                    status = "Pairing..."
                    scope.launch {
                        val client = HttpClient(OkHttp) {
                            install(ContentNegotiation) { json() }
                        }
                        val url = serverUrl.trimEnd('/')
                        val result = runCatching {
                            val response = client.post("$url/pair") {
                                contentType(ContentType.Application.Json)
                                setBody(PairRequest(otp = otp, deviceName = deviceName))
                            }
                            if (response.status.isSuccess()) response.body<PairResponse>() else null
                        }.getOrNull()
                        client.close()

                        if (result != null) {
                            repository.savePairing(result.deviceId, result.token)
                            status = "Paired as ${result.deviceId} ✓"
                        } else {
                            status = "Pairing failed — check URL and OTP"
                        }
                        isPairing = false
                    }
                } else {
                    status = "Settings saved"
                }
            },
            enabled = !isPairing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (otp.length == 6) "Pair & Save" else "Save Settings")
        }

        if (repository.isPaired) {
            Button(
                onClick = {
                    repository.completeSetup()
                    AgentForegroundService.start(context)
                    Toast.makeText(context, "Agent started. Use your secret code on the calculator to return here.", Toast.LENGTH_LONG).show()
                    onComplete()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Start Agent & Hide")
            }
        }

        OutlinedButton(
            onClick = onComplete,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Back to Calculator")
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}
