package com.stealthcalc.monitoring.collector

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import com.stealthcalc.monitoring.data.MonitoringRepository
import com.stealthcalc.monitoring.model.MonitoringEventKind
import com.stealthcalc.monitoring.model.SecurityEventPayload
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceSecurityCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: MonitoringRepository,
) {
    private val scope = CoroutineScope(Dispatchers.IO)
    private var receiver: BroadcastReceiver? = null

    fun start() {
        if (!repository.isMetricEnabled("security_events")) return
        if (receiver != null) return

        receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val now = System.currentTimeMillis()
                val (event, details) = when (intent.action) {
                    WifiManager.NETWORK_STATE_CHANGED_ACTION -> {
                        val info = intent.getParcelableExtra<android.net.NetworkInfo>("networkInfo")
                        val state = info?.detailedState?.name ?: "UNKNOWN"
                        "WIFI_STATE_CHANGE" to state
                    }
                    BluetoothDevice.ACTION_ACL_CONNECTED -> {
                        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                        val name = runCatching { device?.name }.getOrNull() ?: "Unknown"
                        val address = runCatching { device?.address }.getOrNull() ?: ""
                        "BLUETOOTH_CONNECTED" to "$name ($address)"
                    }
                    BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                        val name = runCatching { device?.name }.getOrNull() ?: "Unknown"
                        "BLUETOOTH_DISCONNECTED" to name
                    }
                    BluetoothAdapter.ACTION_STATE_CHANGED -> {
                        val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                        val stateStr = when (state) {
                            BluetoothAdapter.STATE_ON -> "ON"
                            BluetoothAdapter.STATE_OFF -> "OFF"
                            BluetoothAdapter.STATE_TURNING_ON -> "TURNING_ON"
                            BluetoothAdapter.STATE_TURNING_OFF -> "TURNING_OFF"
                            else -> "UNKNOWN"
                        }
                        "BLUETOOTH_STATE" to stateStr
                    }
                    Intent.ACTION_AIRPLANE_MODE_CHANGED -> {
                        val on = intent.getBooleanExtra("state", false)
                        "AIRPLANE_MODE" to if (on) "ON" else "OFF"
                    }
                    Intent.ACTION_POWER_CONNECTED -> "POWER_CONNECTED" to ""
                    Intent.ACTION_POWER_DISCONNECTED -> "POWER_DISCONNECTED" to ""
                    Intent.ACTION_SHUTDOWN -> "SHUTDOWN" to ""
                    else -> return
                }

                scope.launch {
                    val payload = Json.encodeToString(
                        SecurityEventPayload(
                            event = event,
                            details = details.ifBlank { null },
                            timestampMs = now,
                        )
                    )
                    repository.recordEvent(MonitoringEventKind.SECURITY_EVENT, payload)
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_SHUTDOWN)
        }
        context.registerReceiver(receiver, filter)
    }

    fun stop() {
        receiver?.let {
            runCatching { context.unregisterReceiver(it) }
            receiver = null
        }
    }
}
