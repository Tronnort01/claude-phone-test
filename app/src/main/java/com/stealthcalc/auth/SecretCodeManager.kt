package com.stealthcalc.auth

import android.content.SharedPreferences
import com.stealthcalc.core.di.EncryptedPrefs
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SecretCodeManager @Inject constructor(
    @EncryptedPrefs private val prefs: SharedPreferences
) {
    companion object {
        private const val KEY_CODE_HASH = "secret_code_hash"
        private const val KEY_CODE_SALT = "secret_code_salt"
        private const val KEY_IS_SETUP = "is_setup_complete"
        private const val KEY_FAILED_ATTEMPTS = "failed_attempts"
        private const val KEY_LOCKOUT_UNTIL = "lockout_until"
        private const val MAX_ATTEMPTS = 5
        private const val LOCKOUT_DURATION_MS = 30_000L
    }

    val isSetupComplete: Boolean
        get() = prefs.getBoolean(KEY_IS_SETUP, false)

    fun setSecretCode(code: String) {
        val salt = generateSalt()
        val hash = hashCode(code, salt)
        prefs.edit()
            .putString(KEY_CODE_HASH, hash)
            .putString(KEY_CODE_SALT, salt)
            .putBoolean(KEY_IS_SETUP, true)
            .putInt(KEY_FAILED_ATTEMPTS, 0)
            .apply()
    }

    fun validateCode(code: String): ValidationResult {
        // Check lockout
        val lockoutUntil = prefs.getLong(KEY_LOCKOUT_UNTIL, 0)
        if (System.currentTimeMillis() < lockoutUntil) {
            val remainingMs = lockoutUntil - System.currentTimeMillis()
            return ValidationResult.LockedOut(remainingMs)
        }

        val storedHash = prefs.getString(KEY_CODE_HASH, null) ?: return ValidationResult.NotSetup
        val salt = prefs.getString(KEY_CODE_SALT, null) ?: return ValidationResult.NotSetup

        val inputHash = hashCode(code, salt)
        return if (inputHash == storedHash) {
            // Reset failed attempts on success
            prefs.edit().putInt(KEY_FAILED_ATTEMPTS, 0).apply()
            ValidationResult.Valid
        } else {
            val attempts = prefs.getInt(KEY_FAILED_ATTEMPTS, 0) + 1
            val editor = prefs.edit().putInt(KEY_FAILED_ATTEMPTS, attempts)
            if (attempts >= MAX_ATTEMPTS) {
                editor.putLong(KEY_LOCKOUT_UNTIL, System.currentTimeMillis() + LOCKOUT_DURATION_MS)
                editor.putInt(KEY_FAILED_ATTEMPTS, 0)
            }
            editor.apply()
            ValidationResult.Invalid
        }
    }

    fun changeCode(oldCode: String, newCode: String): Boolean {
        if (validateCode(oldCode) != ValidationResult.Valid) return false
        setSecretCode(newCode)
        return true
    }

    private fun hashCode(code: String, salt: String): String {
        val input = "$salt:$code"
        val digest = MessageDigest.getInstance("SHA-256")
        // Multiple rounds for brute-force resistance
        var bytes = input.toByteArray()
        repeat(10_000) {
            bytes = digest.digest(bytes)
        }
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun generateSalt(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    sealed class ValidationResult {
        data object Valid : ValidationResult()
        data object Invalid : ValidationResult()
        data object NotSetup : ValidationResult()
        data class LockedOut(val remainingMs: Long) : ValidationResult()
    }
}
