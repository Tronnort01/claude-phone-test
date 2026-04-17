package com.stealthcalc.monitoring.collector

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import com.stealthcalc.monitoring.data.MonitoringRepository
import com.stealthcalc.monitoring.model.DeviceInfoPayload
import com.stealthcalc.monitoring.model.MonitoringEventKind
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceInfoCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: MonitoringRepository,
) {
    suspend fun collect() {
        if (!repository.isMetricEnabled("device_info")) return

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)

        val stat = StatFs(Environment.getDataDirectory().path)
        val totalStorage = stat.totalBytes
        val freeStorage = stat.availableBytes

        val runningApps = am.runningAppProcesses?.size ?: 0

        val payload = Json.encodeToString(
            DeviceInfoPayload(
                totalStorage = totalStorage,
                freeStorage = freeStorage,
                totalRam = memInfo.totalMem,
                freeRam = memInfo.availMem,
                runningApps = runningApps,
                uptimeMs = SystemClock.elapsedRealtime(),
                androidVersion = Build.VERSION.RELEASE,
                model = Build.MODEL,
                manufacturer = Build.MANUFACTURER,
                timestampMs = System.currentTimeMillis(),
            )
        )
        repository.recordEvent(MonitoringEventKind.DEVICE_INFO, payload)
    }
}
