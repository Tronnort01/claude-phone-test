package com.stealthcalc.server.routes

import com.stealthcalc.server.ErrorResponse
import com.stealthcalc.server.OtpResponse
import com.stealthcalc.server.PairRequest
import com.stealthcalc.server.PairResponse
import com.stealthcalc.server.auth.TokenAuth
import com.stealthcalc.server.db.Devices
import com.stealthcalc.server.db.PairingCodes
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

fun Route.pairingRoutes() {
    post("/pair/request") {
        val otp = TokenAuth.generateOtp()
        val now = System.currentTimeMillis()
        val expiresAt = now + 10 * 60 * 1000L

        transaction {
            PairingCodes.insert {
                it[code] = otp
                it[createdAt] = now
                it[PairingCodes.expiresAt] = expiresAt
                it[used] = false
            }
        }

        call.respond(OtpResponse(otp = otp, expiresAt = expiresAt))
    }

    post("/pair") {
        val request = call.receive<PairRequest>()
        val now = System.currentTimeMillis()

        val valid = transaction {
            val row = PairingCodes.selectAll().where {
                (PairingCodes.code eq request.otp) and
                (PairingCodes.used eq false) and
                (PairingCodes.expiresAt greater now)
            }.firstOrNull()

            if (row != null) {
                PairingCodes.update({ PairingCodes.code eq request.otp }) {
                    it[used] = true
                }
                true
            } else false
        }

        if (!valid) {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid or expired OTP"))
            return@post
        }

        val deviceId = TokenAuth.generateDeviceId()
        val token = TokenAuth.generateToken()
        val tokenHash = TokenAuth.hashToken(token)

        transaction {
            Devices.insert {
                it[id] = deviceId
                it[name] = request.deviceName
                it[role] = "agent"
                it[Devices.tokenHash] = tokenHash
                it[lastSeen] = now
                it[pairedAt] = now
            }
        }

        call.respond(PairResponse(deviceId = deviceId, token = token))
    }
}
