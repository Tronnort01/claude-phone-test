package com.stealthcalc.server.db

import org.jetbrains.exposed.sql.Table

object Devices : Table("devices") {
    val id = varchar("id", 64)
    val name = varchar("name", 256)
    val role = varchar("role", 32)
    val tokenHash = varchar("token_hash", 256)
    val lastSeen = long("last_seen")
    val pairedAt = long("paired_at")
    override val primaryKey = PrimaryKey(id)
}

object Events : Table("events") {
    val id = varchar("id", 64)
    val deviceId = varchar("device_id", 64).references(Devices.id)
    val kind = varchar("kind", 64)
    val payload = text("payload")
    val capturedAt = long("captured_at")
    val receivedAt = long("received_at")
    override val primaryKey = PrimaryKey(id)
}

object RecentState : Table("recent_state") {
    val deviceId = varchar("device_id", 64).references(Devices.id)
    val field = varchar("field", 64)
    val value = text("value")
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(deviceId, field)
}

object PairingCodes : Table("pairing_codes") {
    val code = varchar("code", 16)
    val createdAt = long("created_at")
    val expiresAt = long("expires_at")
    val used = bool("used").default(false)
    override val primaryKey = PrimaryKey(code)
}
