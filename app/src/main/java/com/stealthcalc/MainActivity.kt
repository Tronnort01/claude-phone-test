package com.stealthcalc

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.stealthcalc.auth.AutoLockManager
import com.stealthcalc.auth.BiometricHelper
import com.stealthcalc.auth.PanicHandler
import com.stealthcalc.auth.SecretCodeManager
import com.stealthcalc.core.util.SecureClipboard
import com.stealthcalc.recorder.service.RecorderService
import com.stealthcalc.stealth.navigation.AppRoot
import com.stealthcalc.ui.theme.StealthCalcTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var autoLockManager: AutoLockManager

    @Inject
    lateinit var biometricHelper: BiometricHelper

    @Inject
    lateinit var panicHandler: PanicHandler

    @Inject
    lateinit var secureClipboard: SecureClipboard

    @Inject
    lateinit var secretCodeManager: SecretCodeManager

    private var isStealthVisible by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                autoLockManager.isUnlocked.collect { unlocked ->
                    isStealthVisible = unlocked
                    updateSecureFlag(unlocked)
                }
            }
        }

        // While a recording is in flight we want this activity to show ON
        // TOP of the OS keyguard and to TURN THE SCREEN ON if it's off.
        // Combined with the PARTIAL_WAKE_LOCK held in RecorderService,
        // this means a power-button press / AOD / auto-lock during
        // recording wakes back into our fake lock cover instead of the
        // Pixel keyguard, and the recording keeps running. Flags are
        // cleared when recording stops so normal lock behaviour resumes.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                RecorderService.isRecording.collect { recording ->
                    updateShowWhenLocked(recording)
                }
            }
        }

        setContent {
            StealthCalcTheme {
                AppRoot(
                    isStealthUnlocked = isStealthVisible,
                    onStealthUnlocked = { autoLockManager.unlock() },
                    onLockRequested = { autoLockManager.lock() },
                    secretCodeManager = secretCodeManager,
                )
            }
        }
    }

    override fun onPause() {
        super.onPause()
        autoLockManager.onAppBackgrounded()
    }

    override fun onResume() {
        super.onResume()
        autoLockManager.onAppForegrounded()
    }

    private fun updateSecureFlag(stealthVisible: Boolean) {
        if (stealthVisible) {
            // Prevent screenshots and screen recording when stealth UI is visible
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    private fun updateShowWhenLocked(isRecording: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(isRecording)
            setTurnScreenOn(isRecording)
        } else {
            @Suppress("DEPRECATION")
            if (isRecording) {
                window.addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                )
            } else {
                window.clearFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                )
            }
        }
    }
}
