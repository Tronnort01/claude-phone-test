package com.stealthcalc.settings.viewmodel

import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stealthcalc.auth.AutoLockManager
import com.stealthcalc.auth.BiometricHelper
import com.stealthcalc.auth.IntruderSelfieManager
import com.stealthcalc.auth.SecretCodeManager
import com.stealthcalc.auth.WipeManager
import com.stealthcalc.core.di.EncryptedPrefs
import com.stealthcalc.vault.data.VaultRepository
import com.stealthcalc.vault.service.FileEncryptionService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

data class SettingsState(
    val autoLockDelayMs: Long = 30_000L,
    val isPanicShakeEnabled: Boolean = true,
    val isPanicBackEnabled: Boolean = true,
    val isScreenshotBlocked: Boolean = true,
    val isBiometricEnabled: Boolean = false,
    val isDecoyEnabled: Boolean = false,
    val isOverlayLockEnabled: Boolean = false,
    val useRealLockDuringRecording: Boolean = true,
    val useBlackScreenLock: Boolean = false,
    val isIntruderSelfieEnabled: Boolean = false,
    val isAutoWipeEnabled: Boolean = false,
    val autoWipeThreshold: Int = WipeManager.DEFAULT_WIPE_THRESHOLD,
    // Change code
    val showChangeCodeDialog: Boolean = false,
    val changeCodeError: String? = null,
    val changeCodeSuccess: Boolean = false,
    val showDecoyDialog: Boolean = false,
    val decoyError: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val secretCodeManager: SecretCodeManager,
    private val autoLockManager: AutoLockManager,
    private val biometricHelper: BiometricHelper,
    private val intruderSelfieManager: IntruderSelfieManager,
    private val wipeManager: WipeManager,
    private val vaultRepository: VaultRepository,
    private val encryptionService: FileEncryptionService,
    @EncryptedPrefs private val prefs: SharedPreferences
) : ViewModel() {

    companion object {
        private const val KEY_PANIC_SHAKE = "panic_shake_enabled"
        private const val KEY_PANIC_BACK = "panic_back_enabled"
        private const val KEY_SCREENSHOT_BLOCKED = "screenshot_blocked"
        const val KEY_OVERLAY_LOCK_ENABLED = "overlay_lock_enabled"
        const val KEY_USE_REAL_LOCK_DURING_RECORDING = "use_real_lock_during_recording"
        const val KEY_BLACK_SCREEN_LOCK = "black_screen_lock_enabled"
    }

    private val _state = MutableStateFlow(
        SettingsState(
            autoLockDelayMs = autoLockManager.autoLockDelayMs,
            isPanicShakeEnabled = prefs.getBoolean(KEY_PANIC_SHAKE, true),
            isPanicBackEnabled = prefs.getBoolean(KEY_PANIC_BACK, true),
            isScreenshotBlocked = prefs.getBoolean(KEY_SCREENSHOT_BLOCKED, true),
            isBiometricEnabled = biometricHelper.isBiometricEnabled,
            isDecoyEnabled = secretCodeManager.isDecoyEnabled,
            isOverlayLockEnabled = prefs.getBoolean(KEY_OVERLAY_LOCK_ENABLED, false),
            useRealLockDuringRecording = prefs.getBoolean(KEY_USE_REAL_LOCK_DURING_RECORDING, true),
            useBlackScreenLock = prefs.getBoolean(KEY_BLACK_SCREEN_LOCK, false),
            isIntruderSelfieEnabled = intruderSelfieManager.isEnabled,
            isAutoWipeEnabled = wipeManager.isAutoWipeEnabled,
            autoWipeThreshold = wipeManager.autoWipeThreshold,
        )
    )
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    fun setOverlayLockEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_OVERLAY_LOCK_ENABLED, enabled).apply()
        _state.update { it.copy(isOverlayLockEnabled = enabled) }
    }

    fun setUseRealLockDuringRecording(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_USE_REAL_LOCK_DURING_RECORDING, enabled).apply()
        _state.update { it.copy(useRealLockDuringRecording = enabled) }
    }

    fun setUseBlackScreenLock(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BLACK_SCREEN_LOCK, enabled).apply()
        _state.update { it.copy(useBlackScreenLock = enabled) }
    }

    fun setIntruderSelfieEnabled(enabled: Boolean) {
        intruderSelfieManager.setEnabled(enabled)
        _state.update { it.copy(isIntruderSelfieEnabled = enabled) }
    }

    fun setAutoWipeEnabled(enabled: Boolean) {
        wipeManager.setAutoWipeEnabled(enabled)
        _state.update { it.copy(isAutoWipeEnabled = enabled) }
    }

    fun setAutoWipeThreshold(threshold: Int) {
        wipeManager.setAutoWipeThreshold(threshold)
        _state.update { it.copy(autoWipeThreshold = threshold) }
    }

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

    // --- Biometric ---

    fun toggleBiometric() {
        val new = !_state.value.isBiometricEnabled
        biometricHelper.setBiometricEnabled(new)
        _state.update { it.copy(isBiometricEnabled = new) }
    }

    // --- Decoy PIN ---

    fun showDecoyDialog() {
        _state.update { it.copy(showDecoyDialog = true, decoyError = null) }
    }

    fun hideDecoyDialog() {
        _state.update { it.copy(showDecoyDialog = false, decoyError = null) }
    }

    fun setDecoyCode(code: String, confirmCode: String) {
        if (code != confirmCode) {
            _state.update { it.copy(decoyError = "Codes don't match") }
            return
        }
        if (code.length < 4) {
            _state.update { it.copy(decoyError = "Code must be at least 4 digits") }
            return
        }
        secretCodeManager.setDecoyCode(code)
        _state.update { it.copy(showDecoyDialog = false, isDecoyEnabled = true, decoyError = null) }
    }

    fun disableDecoy() {
        secretCodeManager.disableDecoy()
        _state.update { it.copy(isDecoyEnabled = false) }
    }

    // --- Vault backup ---

    private val _backupFile = MutableSharedFlow<File?>(replay = 0)
    val backupFile = _backupFile.asSharedFlow()

    fun exportVaultBackup() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val files = vaultRepository.getFiles().first()
                val zip = encryptionService.exportVaultBackup(files)
                _backupFile.emit(zip)
            }.onFailure { _backupFile.emit(null) }
        }
    }
}
