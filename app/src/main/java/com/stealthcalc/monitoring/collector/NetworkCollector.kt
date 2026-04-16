package com.stealthcalc.monitoring.collector

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import com.stealthcalc.monitoring.data.MonitoringRepository
import com.stealthcalc.monitoring.model.MonitoringEventKind
import com.stealthcalc.monitoring.model.NetworkPayload
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NetworkCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: MonitoringRepository,
) {
    private val scope = CoroutineScope(Dispatchers.IO)
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun start() {
        if (!repository.isMetricEnabled("network")) return
        if (callback != null) return

        callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                scope.launch { recordNetworkState(true) }
            }

            override fun onLost(network: Network) {
                scope.launch { recordNetworkState(false) }
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                scope.launch { recordNetworkState(true) }
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager?.registerNetworkCallback(request, callback!!)
    }

    fun stop() {
        callback?.let {
            runCatching { connectivityManager?.unregisterNetworkCallback(it) }
            callback = null
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun recordNetworkState(connected: Boolean) {
        val ssid = runCatching {
            wifiManager?.connectionInfo?.ssid?.removePrefix("\"")?.removeSuffix("\"")
        }.getOrNull()?.takeIf { it != "<unknown ssid>" }

        val bssid = runCatching {
            wifiManager?.connectionInfo?.bssid
        }.getOrNull()

        val type = if (ssid != null) "WIFI" else "CELLULAR"

        val payload = Json.encodeToString(
            NetworkPayload(
                type = type,
                ssid = ssid,
                bssid = bssid,
                connected = connected,
            )
        )
        repository.recordEvent(MonitoringEventKind.NETWORK, payload)
    }

    suspend fun collectSnapshot() {
        if (!repository.isMetricEnabled("network")) return
        val caps = connectivityManager?.activeNetwork?.let {
            connectivityManager.getNetworkCapabilities(it)
        }
        recordNetworkState(caps != null)
    }
}
