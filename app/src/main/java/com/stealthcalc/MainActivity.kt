package com.stealthcalc

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
}
