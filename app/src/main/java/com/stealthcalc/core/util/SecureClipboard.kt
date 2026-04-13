package com.stealthcalc.core.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Secure clipboard that auto-clears copied text after a timeout.
 * Prevents sensitive data from lingering in the system clipboard.
 *
 * Usage:
 *   secureClipboard.copy("sensitive text")
 *   // Clipboard auto-clears after 30 seconds
 */
@Singleton
class SecureClipboard @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val DEFAULT_CLEAR_DELAY_MS = 30_000L // 30 seconds
        private const val CLIP_LABEL = "StealthCalc"
    }

    private val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val scope = CoroutineScope(Dispatchers.Main)
    private var clearJob: Job? = null

    /**
     * Copy text to clipboard and schedule auto-clear after [clearDelayMs].
     */
    fun copy(text: String, clearDelayMs: Long = DEFAULT_CLEAR_DELAY_MS) {
        val clip = ClipData.newPlainText(CLIP_LABEL, text)
        clipboardManager.setPrimaryClip(clip)

        // Cancel any existing clear job
        clearJob?.cancel()

        // Schedule clipboard clear
        clearJob = scope.launch {
            delay(clearDelayMs)
            clearClipboard()
        }
    }

    /**
     * Copy text with a label (for notes, passwords, etc.).
     */
    fun copyWithLabel(label: String, text: String, clearDelayMs: Long = DEFAULT_CLEAR_DELAY_MS) {
        val clip = ClipData.newPlainText(label, text)
        clipboardManager.setPrimaryClip(clip)

        clearJob?.cancel()
        clearJob = scope.launch {
            delay(clearDelayMs)
            clearClipboard()
        }
    }

    /**
     * Immediately clear the clipboard.
     */
    fun clearClipboard() {
        clearJob?.cancel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            clipboardManager.clearPrimaryClip()
        } else {
            // Fallback: set empty clip
            clipboardManager.setPrimaryClip(ClipData.newPlainText("", ""))
        }
    }

    /**
     * Check if our content is still on the clipboard.
     */
    fun hasOurContent(): Boolean {
        val clip = clipboardManager.primaryClip ?: return false
        if (clip.description.label == CLIP_LABEL) return true
        return false
    }
}
