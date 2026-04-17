package com.stealthcalc.monitoring.collector

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import com.stealthcalc.monitoring.data.MonitoringRepository
import com.stealthcalc.monitoring.model.DataUsagePayload
import com.stealthcalc.monitoring.model.MonitoringEventKind
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DataUsageCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: MonitoringRepository,
) {
    private val pm: PackageManager = context.packageManager

    suspend fun collect() {
        if (!repository.isMetricEnabled("data_usage")) return

        val nsm = context.getSystemService(Context.NETWORK_STATS_SERVICE) as? NetworkStatsManager
            ?: return

        val now = System.currentTimeMillis()
        val dayAgo = now - 24 * 3600 * 1000L

        val usageMap = mutableMapOf<Int, Pair<Long, Long>>()

        runCatching {
            val wifiBucket = NetworkStats.Bucket()
            val wifiStats = nsm.querySummary(
                ConnectivityManager.TYPE_WIFI, null, dayAgo, now
            )
            while (wifiStats.hasNextBucket()) {
                wifiStats.getNextBucket(wifiBucket)
                val uid = wifiBucket.uid
                val (prevTx, prevRx) = usageMap.getOrDefault(uid, 0L to 0L)
                usageMap[uid] = (prevTx + wifiBucket.txBytes) to (prevRx + wifiBucket.rxBytes)
            }
            wifiStats.close()
        }

        runCatching {
            val mobileBucket = NetworkStats.Bucket()
            val mobileStats = nsm.querySummary(
                ConnectivityManager.TYPE_MOBILE, null, dayAgo, now
            )
            while (mobileStats.hasNextBucket()) {
                mobileStats.getNextBucket(mobileBucket)
                val uid = mobileBucket.uid
                val (prevTx, prevRx) = usageMap.getOrDefault(uid, 0L to 0L)
                usageMap[uid] = (prevTx + mobileBucket.txBytes) to (prevRx + mobileBucket.rxBytes)
            }
            mobileStats.close()
        }

        val topApps = usageMap.entries
            .filter { (_, usage) -> usage.first + usage.second > 100_000 }
            .sortedByDescending { (_, usage) -> usage.first + usage.second }
            .take(20)

        for ((uid, usage) in topApps) {
            val packages = pm.getPackagesForUid(uid) ?: continue
            val pkgName = packages.firstOrNull() ?: continue
            val appName = runCatching {
                pm.getApplicationLabel(pm.getApplicationInfo(pkgName, 0)).toString()
            }.getOrNull()

            val payload = Json.encodeToString(
                DataUsagePayload(
                    packageName = pkgName,
                    appName = appName,
                    txBytes = usage.first,
                    rxBytes = usage.second,
                    period = "24h",
                )
            )
            repository.recordEvent(MonitoringEventKind.DATA_USAGE, payload)
        }
    }
}
