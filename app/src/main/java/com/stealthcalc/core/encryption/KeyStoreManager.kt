package com.stealthcalc.core.encryption

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.stealthcalc.core.di.EncryptedPrefs
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the database encryption key using the Android Keystore.
 * The actual DB passphrase is generated once and stored encrypted via the Keystore.
 */
@Singleton
class KeyStoreManager @Inject constructor(
    @EncryptedPrefs private val prefs: SharedPreferences,
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val KEYSTORE_ALIAS = "stealth_db_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_DB_PASSPHRASE = "encrypted_db_passphrase"
        private const val KEY_DB_IV = "db_passphrase_iv"
        private const val GCM_TAG_LENGTH = 128
    }

    /**
     * Returns the SQLCipher database passphrase.
     * On first call, generates a random passphrase and encrypts it with the Keystore.
     * On subsequent calls, decrypts and returns the stored passphrase.
     */
    fun getDatabasePassphrase(): ByteArray {
        val stored = prefs.getString(KEY_DB_PASSPHRASE, null)
        return if (stored != null) {
            decryptPassphrase()
        } else {
            generateAndStorePassphrase()
        }
    }

    private fun generateAndStorePassphrase(): ByteArray {
        // Generate a random 32-byte passphrase
        val passphrase = ByteArray(32)
        java.security.SecureRandom().nextBytes(passphrase)

        // Encrypt it with the Keystore key
        val key = getOrCreateKeystoreKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)

        val encrypted = cipher.doFinal(passphrase)
        val iv = cipher.iv

        // Store encrypted passphrase and IV
        prefs.edit()
            .putString(KEY_DB_PASSPHRASE, encrypted.toHex())
            .putString(KEY_DB_IV, iv.toHex())
            .apply()

        return passphrase
    }

    private fun decryptPassphrase(): ByteArray {
        val encryptedHex = prefs.getString(KEY_DB_PASSPHRASE, null)
            ?: throw IllegalStateException("No stored passphrase")
        val ivHex = prefs.getString(KEY_DB_IV, null)
            ?: throw IllegalStateException("No stored IV")

        val encrypted = encryptedHex.hexToBytes()
        val iv = ivHex.hexToBytes()

        val key = getOrCreateKeystoreKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))

        return cipher.doFinal(encrypted)
    }

    private fun getOrCreateKeystoreKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)

        val existingKey = keyStore.getEntry(KEYSTORE_ALIAS, null)
        if (existingKey is KeyStore.SecretKeyEntry) {
            return existingKey.secretKey
        }

        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        keyGen.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return keyGen.generateKey()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.hexToBytes(): ByteArray {
        val len = length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            data[i / 2] = ((Character.digit(this[i], 16) shl 4) + Character.digit(this[i + 1], 16)).toByte()
        }
        return data
    }
}
