package com.stealthcalc.monitoring.network

import com.stealthcalc.monitoring.data.MonitoringRepository
import com.stealthcalc.monitoring.model.DeviceState
import com.stealthcalc.monitoring.model.EventBatch
import com.stealthcalc.monitoring.model.EventPayload
import com.stealthcalc.monitoring.model.MonitoringEvent
import com.stealthcalc.monitoring.model.PairRequest
import com.stealthcalc.monitoring.model.PairResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AgentApiClient @Inject constructor(
    private val repository: MonitoringRepository,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val client: HttpClient by lazy {
        HttpClient(OkHttp) {
            install(ContentNegotiation) { json(json) }
            install(WebSockets)
        }
    }

    private val baseUrl: String get() = repository.serverUrl.trimEnd('/')

    suspend fun pair(otp: String, deviceName: String): PairResponse? {
        if (baseUrl.isBlank()) return null
        return runCatching {
            val response = client.post("$baseUrl/pair") {
                contentType(ContentType.Application.Json)
                setBody(PairRequest(otp = otp, deviceName = deviceName))
            }
            if (response.status.isSuccess()) response.body<PairResponse>() else null
        }.getOrNull()
    }

    suspend fun uploadBatch(events: List<MonitoringEvent>): Boolean {
        if (baseUrl.isBlank() || !repository.isPaired) return false
        return runCatching {
            val batch = EventBatch(
                deviceId = repository.deviceId,
                events = events.map { e ->
                    EventPayload(
                        id = e.id,
                        kind = e.kind.name,
                        payload = e.payload,
                        capturedAt = e.capturedAt,
                    )
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

    suspend fun getDeviceState(deviceId: String): DeviceState? {
        if (baseUrl.isBlank() || !repository.isPaired) return null
        return runCatching {
            val response = client.get("$baseUrl/state/$deviceId") {
                bearerAuth(repository.authToken)
            }
            if (response.status.isSuccess()) response.body<DeviceState>() else null
        }.getOrNull()
    }

    suspend fun getEvents(deviceId: String, since: Long = 0): List<EventPayload> {
        if (baseUrl.isBlank() || !repository.isPaired) return emptyList()
        return runCatching {
            val response = client.get("$baseUrl/events/$deviceId?since=$since") {
                bearerAuth(repository.authToken)
            }
            if (response.status.isSuccess()) response.body<List<EventPayload>>() else emptyList()
        }.getOrDefault(emptyList())
    }

    fun close() {
        client.close()
    }
}
