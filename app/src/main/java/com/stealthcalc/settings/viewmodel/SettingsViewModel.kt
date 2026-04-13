package com.stealthcalc.settings.viewmodel

import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import com.stealthcalc.auth.AutoLockManager
import com.stealthcalc.auth.SecretCodeManager
import com.stealthcalc.core.di.EncryptedPrefs
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

data class SettingsState(
    val autoLockDelayMs: Long = 30_000L,
    val isPanicShakeEnabled: Boolean = true,
    val isPanicBackEnabled: Boolean = true,
    val isScreenshotBlocked: Boolean = true,
    // Change code
    val showChangeCodeDialog: Boolean = false,
    val changeCodeError: String? = null,
    val changeCodeSuccess: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val secretCodeManager: SecretCodeManager,
    private val autoLockManager: AutoLockManager,
    @EncryptedPrefs private val prefs: SharedPreferences
) : ViewModel() {

    companion object {
        private const val KEY_PANIC_SHAKE = "panic_shake_enabled"
        private const val KEY_PANIC_BACK = "panic_back_enabled"
        private const val KEY_SCREENSHOT_BLOCKED = "screenshot_blocked"
    }

    private val _state = MutableStateFlow(
        SettingsState(
            autoLockDelayMs = autoLockManager.autoLockDelayMs,
            isPanicShakeEnabled = prefs.getBoolean(KEY_PANIC_SHAKE, true),
            isPanicBackEnabled = prefs.getBoolean(KEY_PANIC_BACK, true),
            isScreenshotBlocked = prefs.getBoolean(KEY_SCREENSHOT_BLOCKED, true),
        )
    )
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    val autoLockOptions = listOf(
        10_000L to "10 seconds",
        30_000L to "30 seconds",
        60_000L to "1 minute",
        300_000L to "5 minutes",
    )

    fun setAutoLockDelay(delayMs: Long) {
        autoLockManager.setAutoLockDelay(delayMs)
        _state.update { it.copy(autoLockDelayMs = delayMs) }
    }

    fun togglePanicShake() {
        val new = !_state.value.isPanicShakeEnabled
        prefs.edit().putBoolean(KEY_PANIC_SHAKE, new).apply()
        _state.update { it.copy(isPanicShakeEnabled = new) }
    }

    fun togglePanicBack() {
        val new = !_state.value.isPanicBackEnabled
        prefs.edit().putBoolean(KEY_PANIC_BACK, new).apply()
        _state.update { it.copy(isPanicBackEnabled = new) }
    }

    fun toggleScreenshotBlock() {
        val new = !_state.value.isScreenshotBlocked
        prefs.edit().putBoolean(KEY_SCREENSHOT_BLOCKED, new).apply()
        _state.update { it.copy(isScreenshotBlocked = new) }
    }

    fun showChangeCodeDialog() {
        _state.update { it.copy(showChangeCodeDialog = true, changeCodeError = null, changeCodeSuccess = false) }
    }

    fun hideChangeCodeDialog() {
        _state.update { it.copy(showChangeCodeDialog = false, changeCodeError = null, changeCodeSuccess = false) }
    }

    fun changeCode(oldCode: String, newCode: String, confirmCode: String): Boolean {
        if (newCode != confirmCode) {
            _state.update { it.copy(changeCodeError = "New codes don't match") }
            return false
        }
        if (newCode.length < 4) {
            _state.update { it.copy(changeCodeError = "Code must be at least 4 digits") }
            return false
        }
        val success = secretCodeManager.changeCode(oldCode, newCode)
        if (success) {
            _state.update { it.copy(changeCodeSuccess = true, changeCodeError = null, showChangeCodeDialog = false) }
            return true
        } else {
            _state.update { it.copy(changeCodeError = "Current code is incorrect") }
            return false
        }
    }
}
