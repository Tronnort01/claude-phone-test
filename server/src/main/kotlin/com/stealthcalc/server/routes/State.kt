package com.stealthcalc.server.routes

import com.stealthcalc.server.DeviceState
import com.stealthcalc.server.ErrorResponse
import com.stealthcalc.server.auth.TokenAuth
import com.stealthcalc.server.db.Devices
import com.stealthcalc.server.db.RecentState
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

fun Route.stateRoutes() {
    get("/state/{deviceId}") {
        val token = call.request.header("Authorization")?.removePrefix("Bearer ")
        if (token == null || TokenAuth.authenticateDevice(token) == null) {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
            return@get
        }

        val deviceId = call.parameters["deviceId"] ?: return@get

        val result = transaction {
            val device = Devices.selectAll().where { Devices.id eq deviceId }.firstOrNull()
                ?: return@transaction null

            val stateMap = RecentState.selectAll()
                .where { RecentState.deviceId eq deviceId }
                .associate { it[RecentState.field] to it[RecentState.value] }

            DeviceState(
                deviceId = deviceId,
                deviceName = device[Devices.name],
                lastSeen = device[Devices.lastSeen],
                currentApp = stateMap["currentApp"],
                batteryLevel = stateMap["batteryLevel"]?.toIntOrNull(),
                isCharging = stateMap["isCharging"]?.toBooleanStrictOrNull(),
                wifiSsid = stateMap["wifiSsid"],
                isScreenOn = stateMap["isScreenOn"]?.toBooleanStrictOrNull(),
                latitude = stateMap["latitude"]?.toDoubleOrNull(),
                longitude = stateMap["longitude"]?.toDoubleOrNull(),
            )
        }

        if (result != null) {
            call.respond(result)
        } else {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Device not found"))
        }
    }
}
