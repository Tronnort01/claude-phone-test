package com.stealthagent.data

import android.content.SharedPreferences
import com.stealthagent.model.MonitoringEvent
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AgentRepository @Inject constructor(
    private val dao: AgentDao,
    @AgentPrefs private val prefs: SharedPreferences,
) {
    companion object {
        const val KEY_SERVER_URL = "server_url"
        const val KEY_DIRECT_URL = "direct_url"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_AUTH_TOKEN = "auth_token"
        const val KEY_DEVICE_NAME = "device_name"
        const val KEY_SETUP_DONE = "setup_done"
        const val KEY_SECRET_CODE = "secret_code_hash"
        const val KEY_LAST_SYNC = "last_sync"
    }

    val serverUrl: String get() = prefs.getString(KEY_SERVER_URL, "") ?: ""
    val directUrl: String get() = prefs.getString(KEY_DIRECT_URL, "") ?: ""
    val deviceId: String get() = prefs.getString(KEY_DEVICE_ID, "") ?: ""
    val authToken: String get() = prefs.getString(KEY_AUTH_TOKEN, "") ?: ""
    val deviceName: String get() = prefs.getString(KEY_DEVICE_NAME, "") ?: ""
    val isSetupDone: Boolean get() = prefs.getBoolean(KEY_SETUP_DONE, false)
    val isPaired: Boolean get() = authToken.isNotEmpty() && deviceId.isNotEmpty()
    val lastSync: Long get() = prefs.getLong(KEY_LAST_SYNC, 0L)

    fun setServerUrl(url: String) { prefs.edit().putString(KEY_SERVER_URL, url).apply() }
    fun setDirectUrl(url: String) { prefs.edit().putString(KEY_DIRECT_URL, url).apply() }
    fun setDeviceName(name: String) { prefs.edit().putString(KEY_DEVICE_NAME, name).apply() }
    fun setLastSync(ts: Long) { prefs.edit().putLong(KEY_LAST_SYNC, ts).apply() }

    fun savePairing(deviceId: String, token: String) {
        prefs.edit().putString(KEY_DEVICE_ID, deviceId).putString(KEY_AUTH_TOKEN, token).apply()
    }

    fun completeSetup() { prefs.edit().putBoolean(KEY_SETUP_DONE, true).apply() }

    fun setSecretCode(hash: String) { prefs.edit().putString(KEY_SECRET_CODE, hash).apply() }
    val secretCodeHash: String get() = prefs.getString(KEY_SECRET_CODE, "") ?: ""

    suspend fun recordEvent(kind: String, payload: String) {
        dao.insert(MonitoringEvent(kind = kind, payload = payload))
    }

    suspend fun getUnsent(limit: Int = 500) = dao.getUnsent(limit)
    suspend fun markUploaded(ids: List<String>) = dao.markUploaded(ids)
    suspend fun pruneOldEvents(maxAgeMs: Long = 7 * 24 * 3600 * 1000L) {
        dao.deleteUploadedBefore(System.currentTimeMillis() - maxAgeMs)
    }
    suspend fun unsentCount() = dao.unsentCount()
}
