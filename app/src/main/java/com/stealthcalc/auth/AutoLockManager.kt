package com.stealthcalc.auth

import android.content.SharedPreferences
import com.stealthcalc.core.di.EncryptedPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AutoLockManager @Inject constructor(
    @EncryptedPrefs private val prefs: SharedPreferences
) {
    companion object {
        private const val KEY_AUTO_LOCK_MS = "auto_lock_delay_ms"
        private const val DEFAULT_LOCK_DELAY_MS = 30_000L // 30 seconds
    }

    private val _isUnlocked = MutableStateFlow(false)
    val isUnlocked: StateFlow<Boolean> = _isUnlocked.asStateFlow()

    private var backgroundTimestamp: Long = 0L

    val autoLockDelayMs: Long
        get() = prefs.getLong(KEY_AUTO_LOCK_MS, DEFAULT_LOCK_DELAY_MS)

    fun setAutoLockDelay(delayMs: Long) {
        prefs.edit().putLong(KEY_AUTO_LOCK_MS, delayMs).apply()
    }

    fun unlock() {
        _isUnlocked.value = true
    }

    fun lock() {
        _isUnlocked.value = false
    }

    fun onAppBackgrounded() {
        backgroundTimestamp = System.currentTimeMillis()
    }

    fun onAppForegrounded() {
        if (_isUnlocked.value && backgroundTimestamp > 0) {
            val elapsed = System.currentTimeMillis() - backgroundTimestamp
            if (elapsed > autoLockDelayMs) {
                lock()
            }
        }
        backgroundTimestamp = 0L
    }
}
