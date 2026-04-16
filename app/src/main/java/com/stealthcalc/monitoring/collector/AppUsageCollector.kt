package com.stealthcalc.monitoring.collector

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import com.stealthcalc.monitoring.data.MonitoringRepository
import com.stealthcalc.monitoring.model.AppUsagePayload
import com.stealthcalc.monitoring.model.MonitoringEventKind
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppUsageCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: MonitoringRepository,
) {
    private val usageStatsManager: UsageStatsManager? =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
    private val pm: PackageManager = context.packageManager
    private var lastPollTime: Long = System.currentTimeMillis()

    fun hasPermission(): Boolean {
        val usm = usageStatsManager ?: return false
        val now = System.currentTimeMillis()
        val stats = usm.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY, now - 60_000, now
        )
        return stats.isNotEmpty()
    }

    suspend fun collect() {
        if (!repository.isMetricEnabled("app_usage")) return
        val usm = usageStatsManager ?: return

        val now = System.currentTimeMillis()
        val events = usm.queryEvents(lastPollTime, now)
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                event.eventType == UsageEvents.Event.MOVE_TO_BACKGROUND
            ) {
                val appName = runCatching {
                    pm.getApplicationLabel(
                        pm.getApplicationInfo(event.packageName, 0)
                    ).toString()
                }.getOrNull()
                val payload = Json.encodeToString(
                    AppUsagePayload(
                        packageName = event.packageName,
                        appName = appName,
                        event = if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) "FOREGROUND" else "BACKGROUND",
                        timestampMs = event.timeStamp,
                    )
                )
                repository.recordEvent(MonitoringEventKind.APP_USAGE, payload)
            }
        }
        lastPollTime = now
    }
}
