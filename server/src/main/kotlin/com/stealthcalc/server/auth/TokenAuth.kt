package com.stealthcalc.server.auth

import at.favre.lib.crypto.bcrypt.BCrypt
import com.stealthcalc.server.db.Devices
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.security.SecureRandom
import java.util.UUID

object TokenAuth {
    private val random = SecureRandom()

    fun generateToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun hashToken(token: String): String =
        BCrypt.withDefaults().hashToString(10, token.toCharArray())

    fun verifyToken(token: String, hash: String): Boolean =
        BCrypt.verifyer().verify(token.toCharArray(), hash).verified

    fun generateDeviceId(): String = UUID.randomUUID().toString().take(16)

    fun generateOtp(): String {
        val otp = random.nextInt(1_000_000)
        return "%06d".format(otp)
    }

    fun authenticateDevice(token: String): String? {
        return transaction {
            Devices.selectAll().forEach { row ->
                if (verifyToken(token, row[Devices.tokenHash])) {
                    return@transaction row[Devices.id]
                }
            }
            null
        }
    }
}
