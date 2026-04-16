package com.stealthcalc.monitoring.collector

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.stealthcalc.monitoring.data.MonitoringRepository
import com.stealthcalc.monitoring.model.MonitoringEventKind
import com.stealthcalc.monitoring.model.ScreenEventPayload
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScreenStateCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: MonitoringRepository,
) {
    private val scope = CoroutineScope(Dispatchers.IO)
    private var receiver: BroadcastReceiver? = null

    fun start() {
        if (!repository.isMetricEnabled("screen_events")) return
        if (receiver != null) return

        receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val event = when (intent.action) {
                    Intent.ACTION_SCREEN_ON -> "SCREEN_ON"
                    Intent.ACTION_SCREEN_OFF -> "SCREEN_OFF"
                    Intent.ACTION_USER_PRESENT -> "USER_PRESENT"
                    else -> return
                }
                scope.launch {
                    val payload = Json.encodeToString(
                        ScreenEventPayload(event = event, timestampMs = System.currentTimeMillis())
                    )
                    repository.recordEvent(MonitoringEventKind.SCREEN_EVENT, payload)
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
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
