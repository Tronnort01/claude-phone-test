package com.stealthcalc.auth

import android.content.SharedPreferences
import androidx.activity.ComponentActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import com.stealthcalc.core.di.EncryptedPrefs
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages biometric (fingerprint/face) quick-unlock.
 * After the user enters their PIN once in a session, they can use biometrics
 * to re-enter if the app auto-locks within the same session.
 */
@Singleton
class BiometricHelper @Inject constructor(
    @EncryptedPrefs private val prefs: SharedPreferences
) {
    companion object {
        private const val KEY_BIOMETRIC_ENABLED = "biometric_enabled"
        private const val KEY_SESSION_AUTHENTICATED = "session_authenticated"
    }

    val isBiometricEnabled: Boolean
        get() = prefs.getBoolean(KEY_BIOMETRIC_ENABLED, false)

    // Whether the user has authenticated with PIN at least once this session
    private var sessionAuthenticated: Boolean = false

    // The PIN from the current session (held in memory only, never persisted)
    private var sessionPin: String? = null

    fun canUseBiometric(): Boolean = isBiometricEnabled && sessionAuthenticated

    fun setBiometricEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BIOMETRIC_ENABLED, enabled).apply()
    }

    fun onPinAuthenticated(pin: String) {
        sessionAuthenticated = true
        sessionPin = pin
    }

    fun getSessionPin(): String? = sessionPin

    fun clearSession() {
        sessionAuthenticated = false
        sessionPin = null
    }

    fun isBiometricAvailable(activity: ComponentActivity): Boolean {
        val biometricManager = BiometricManager.from(activity)
        return biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        ) == BiometricManager.BIOMETRIC_SUCCESS
    }

    fun showBiometricPrompt(
        activity: ComponentActivity,
        onSuccess: (pin: String) -> Unit,
        onFailure: () -> Unit
    ) {
        if (!canUseBiometric()) {
            onFailure()
            return
        }

        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val pin = sessionPin
                if (pin != null) {
                    onSuccess(pin)
                } else {
                    onFailure()
                }
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onFailure()
            }

            override fun onAuthenticationFailed() {
                // Individual attempt failed, biometric prompt handles retry
            }
        }

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Stealth")
            .setSubtitle("Use your fingerprint to unlock")
            .setNegativeButtonText("Use PIN")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()

        BiometricPrompt(activity, callback).authenticate(promptInfo)
    }
}
