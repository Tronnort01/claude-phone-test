package com.stealthcalc.monitoring.collector

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.net.wifi.WifiManager
import com.stealthcalc.core.di.EncryptedPrefs
import com.stealthcalc.core.logging.AppLogger
import com.stealthcalc.monitoring.data.MonitoringRepository
import com.stealthcalc.monitoring.model.MonitoringEventKind
import com.stealthcalc.monitoring.model.SecurityEventPayload
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WifiAlertCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: MonitoringRepository,
    @EncryptedPrefs private val prefs: SharedPreferences,
) {
    companion object {
        private const val KEY_KNOWN_NETWORKS = "known_wifi_networks"
    }

    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    var knownNetworks: Set<String>
        get() = prefs.getStringSet(KEY_KNOWN_NETWORKS, emptySet()) ?: emptySet()
        set(value) { prefs.edit().putStringSet(KEY_KNOWN_NETWORKS, value).apply() }

    fun addKnownNetwork(ssid: String) {
        knownNetworks = knownNetworks + ssid
    }

    fun removeKnownNetwork(ssid: String) {
        knownNetworks = knownNetworks - ssid
    }

    @SuppressLint("MissingPermission")
    suspend fun collect() {
        if (!repository.isMetricEnabled("wifi_alerts")) return
        val wm = wifiManager ?: return

        val info = wm.connectionInfo ?: return
        val ssid = info.ssid?.removePrefix("\"")?.removeSuffix("\"")
            ?.takeIf { it != "<unknown ssid>" } ?: return

        val known = knownNetworks
        if (known.isEmpty()) {
            addKnownNetwork(ssid)
            return
        }

        if (ssid !in known) {
            val payload = Json.encodeToString(
                SecurityEventPayload(
                    event = "UNKNOWN_WIFI_NETWORK",
                    details = "Connected to unknown network: $ssid",
                    timestampMs = System.currentTimeMillis(),
                )
            )
            repository.recordEvent(MonitoringEventKind.SECURITY_EVENT, payload)
            AppLogger.log(context, "[agent]", "Unknown WiFi alert: $ssid")
        }
    }
}
