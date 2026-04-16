package com.stealthcalc.server.routes

import com.stealthcalc.server.auth.TokenAuth
import com.stealthcalc.server.db.Events
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

fun Route.liveRoutes() {
    webSocket("/live/{deviceId}") {
        val token = call.request.queryParameters["token"]
        if (token == null || TokenAuth.authenticateDevice(token) == null) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Unauthorized"))
            return@webSocket
        }

        val deviceId = call.parameters["deviceId"] ?: run {
            close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Missing deviceId"))
            return@webSocket
        }

        var lastSeen = System.currentTimeMillis()

        while (isActive) {
            val newEvents = transaction {
                Events.selectAll().where {
                    (Events.deviceId eq deviceId) and (Events.receivedAt greater lastSeen)
                }.orderBy(Events.receivedAt).map { row ->
                    com.stealthcalc.server.EventPayload(
                        id = row[Events.id],
                        kind = row[Events.kind],
                        payload = row[Events.payload],
                        capturedAt = row[Events.capturedAt],
                    )
                }
            }

            if (newEvents.isNotEmpty()) {
                lastSeen = System.currentTimeMillis()
                val json = Json.encodeToString(newEvents)
                send(Frame.Text(json))
            }

            delay(2000)
        }
    }
}
