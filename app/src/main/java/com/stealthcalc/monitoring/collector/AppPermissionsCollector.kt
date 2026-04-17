package com.stealthcalc.monitoring.collector

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import com.stealthcalc.monitoring.data.MonitoringRepository
import com.stealthcalc.monitoring.model.AppPermissionPayload
import com.stealthcalc.monitoring.model.MonitoringEventKind
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppPermissionsCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: MonitoringRepository,
) {
    private var lastSnapshot: Set<String> = emptySet()

    suspend fun collect() {
        if (!repository.isMetricEnabled("app_permissions")) return

        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val apps = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)

        val currentKeys = apps.mapNotNull { it.packageName }.toSet()
        if (currentKeys == lastSnapshot && lastSnapshot.isNotEmpty()) return
        lastSnapshot = currentKeys

        for (pkgInfo in apps) {
            val permissions = pkgInfo.requestedPermissions?.toList() ?: continue
            if (permissions.isEmpty()) continue

            val granted = mutableListOf<String>()
            pkgInfo.requestedPermissions?.forEachIndexed { index, perm ->
                if (pkgInfo.requestedPermissionsFlags != null &&
                    (pkgInfo.requestedPermissionsFlags!![index] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0) {
                    if (isDangerous(pm, perm)) {
                        granted.add(perm.substringAfterLast('.'))
                    }
                }
            }

            if (granted.isEmpty()) continue

            val appName = runCatching {
                pm.getApplicationLabel(pm.getApplicationInfo(pkgInfo.packageName, 0)).toString()
            }.getOrDefault(pkgInfo.packageName)

            val payload = Json.encodeToString(
                AppPermissionPayload(
                    packageName = pkgInfo.packageName,
                    appName = appName,
                    permissions = permissions.map { it.substringAfterLast('.') },
                    dangerousGranted = granted,
                )
            )
            repository.recordEvent(MonitoringEventKind.APP_PERMISSIONS, payload)
        }
    }

    private fun isDangerous(pm: PackageManager, permission: String): Boolean {
        return runCatching {
            val info = pm.getPermissionInfo(permission, 0)
            info.protection == PermissionInfo.PROTECTION_DANGEROUS
        }.getOrDefault(false)
    }
}
