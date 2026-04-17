package com.stealthcalc.monitoring.collector

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.stealthcalc.monitoring.data.MonitoringRepository
import com.stealthcalc.monitoring.model.MonitoringEventKind
import com.stealthcalc.monitoring.model.SimChangePayload
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SimChangeCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: MonitoringRepository,
) {
    private val scope = CoroutineScope(Dispatchers.IO)
    private var receiver: BroadcastReceiver? = null
    private var lastSimState: String? = null

    fun start() {
        if (!repository.isMetricEnabled("sim_change")) return
        if (receiver != null) return

        receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == "android.intent.action.SIM_STATE_CHANGED") {
                    scope.launch { recordSimState() }
                }
            }
        }
        context.registerReceiver(receiver, IntentFilter("android.intent.action.SIM_STATE_CHANGED"))
    }

    fun stop() {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
    }

    suspend fun collect() {
        if (!repository.isMetricEnabled("sim_change")) return
        recordSimState()
    }

    @Suppress("MissingPermission")
    private suspend fun recordSimState() {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return
        val state = when (tm.simState) {
            TelephonyManager.SIM_STATE_READY -> "READY"
            TelephonyManager.SIM_STATE_ABSENT -> "ABSENT"
            TelephonyManager.SIM_STATE_PIN_REQUIRED -> "PIN_REQUIRED"
            TelephonyManager.SIM_STATE_PUK_REQUIRED -> "PUK_REQUIRED"
            TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "NETWORK_LOCKED"
            else -> "UNKNOWN"
        }

        if (state == lastSimState) return
        lastSimState = state

        val carrierName = tm.simOperatorName
        val countryIso = tm.simCountryIso

        val phoneNumber = if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED) {
            runCatching { tm.line1Number }.getOrNull()
        } else null

        val payload = Json.encodeToString(
            SimChangePayload(
                simState = state,
                carrierName = carrierName,
                countryIso = countryIso,
                phoneNumber = phoneNumber,
                timestampMs = System.currentTimeMillis(),
            )
        )
        repository.recordEvent(MonitoringEventKind.SIM_CHANGE, payload)
    }
}
