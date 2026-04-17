package com.stealthcalc.server.routes

import com.stealthcalc.server.ErrorResponse
import com.stealthcalc.server.auth.TokenAuth
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class CommandRequest(
    val type: String,
    val params: Map<String, String> = emptyMap(),
)

private val commandChannels = ConcurrentHashMap<String, Channel<String>>()

fun Route.commandRoutes() {
    webSocket("/commands/{deviceId}") {
        val token = call.request.queryParameters["token"]
        if (token == null || TokenAuth.authenticateDevice(token) == null) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Unauthorized"))
            return@webSocket
        }

        val deviceId = call.parameters["deviceId"] ?: run {
            close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Missing deviceId"))
            return@webSocket
        }

        val channel = Channel<String>(Channel.BUFFERED)
        commandChannels[deviceId] = channel

        try {
            for (command in channel) {
                send(Frame.Text(command))
            }
        } finally {
            commandChannels.remove(deviceId)
            channel.close()
        }
    }

    post("/commands/{deviceId}/send") {
        val token = call.request.header("Authorization")?.removePrefix("Bearer ")
        if (token == null || TokenAuth.authenticateDevice(token) == null) {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
            return@post
        }

        val deviceId = call.parameters["deviceId"] ?: return@post
        val command = call.receive<CommandRequest>()
        val json = Json.encodeToString(command)

        val channel = commandChannels[deviceId]
        if (channel != null) {
            channel.trySend(json)
            call.respond(mapOf("status" to "sent"))
        } else {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Device not connected to command channel"))
        }
    }
}
