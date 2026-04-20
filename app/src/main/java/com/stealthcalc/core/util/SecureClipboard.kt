package com.stealthcalc.core.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.stealthcalc.core.di.EncryptedPrefs
import com.stealthcalc.settings.viewmodel.SettingsViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SecureClipboard @Inject constructor(
    @ApplicationContext private val context: Context,
    @EncryptedPrefs private val prefs: SharedPreferences,
) {
    companion object {
        private const val CLIP_LABEL = "StealthCalc"
    }

    private val clearDelayMs: Long
        get() = prefs.getLong(SettingsViewModel.KEY_CLIPBOARD_TIMEOUT_MS, 30_000L)

    private val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val scope = CoroutineScope(Dispatchers.Main)
    private var clearJob: Job? = null

    fun copy(text: String) {
        val clip = ClipData.newPlainText(CLIP_LABEL, text)
        clipboardManager.setPrimaryClip(clip)
        scheduleAutoClear()
    }

    fun copyWithLabel(label: String, text: String) {
        val clip = ClipData.newPlainText(label, text)
        clipboardManager.setPrimaryClip(clip)
        scheduleAutoClear()
    }

    private fun scheduleAutoClear() {
        clearJob?.cancel()
        val ms = clearDelayMs
        if (ms < 0) return  // -1 = never
        clearJob = scope.launch {
            delay(ms)
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
