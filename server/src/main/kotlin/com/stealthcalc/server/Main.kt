package com.stealthcalc.server

import com.stealthcalc.server.db.DatabaseFactory
import com.stealthcalc.server.routes.startRetentionCleanup
import com.stealthcalc.server.routes.commandRoutes
import com.stealthcalc.server.routes.eventRoutes
import com.stealthcalc.server.routes.fileRoutes
import com.stealthcalc.server.routes.liveRoutes
import com.stealthcalc.server.routes.loadFileIndex
import com.stealthcalc.server.routes.pairingRoutes
import com.stealthcalc.server.routes.stateRoutes
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.callloging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.http.*
import kotlinx.serialization.json.Json
import java.time.Duration

fun main() {
    val host = System.getenv("HOST") ?: "0.0.0.0"
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val dbPath = System.getenv("DB_PATH") ?: "stealthcalc.db"

    val retentionDays = System.getenv("RETENTION_DAYS")?.toIntOrNull() ?: 30

    DatabaseFactory.init(dbPath)
    loadFileIndex()
    startRetentionCleanup(retentionDays)

    embeddedServer(Netty, port = port, host = host) {
        configureServer()
    }.start(wait = true)
}

fun Application.configureServer() {
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        })
    }

    install(WebSockets) {
        pingPeriod = Duration.ofSeconds(15)
        timeout = Duration.ofSeconds(30)
        maxFrameSize = Long.MAX_VALUE
    }

    install(CallLogging)

    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.application.environment.log.error("Unhandled error", cause)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse(cause.message ?: "Unknown error"))
        }
    }

    routing {
        get("/health") {
            call.respond(mapOf("status" to "ok", "time" to System.currentTimeMillis()))
        }

        pairingRoutes()
        eventRoutes()
        stateRoutes()
        liveRoutes()
        fileRoutes()
        commandRoutes()
    }
}
