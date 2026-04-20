package com.stealthcalc.auth

import android.content.Context
import android.content.SharedPreferences
import com.stealthcalc.core.di.EncryptedPrefs
import com.stealthcalc.core.logging.AppLogger
import com.stealthcalc.vault.data.VaultRepository
import com.stealthcalc.vault.service.FileEncryptionService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WipeManager @Inject constructor(
    @ApplicationContext private val context: Context,
    @EncryptedPrefs private val prefs: SharedPreferences,
    private val vaultRepository: VaultRepository,
    private val encryptionService: FileEncryptionService,
) {
    companion object {
        const val KEY_AUTO_WIPE_ENABLED = "auto_wipe_enabled"
        const val KEY_AUTO_WIPE_THRESHOLD = "auto_wipe_threshold"
        const val DEFAULT_WIPE_THRESHOLD = 10
    }

    val isAutoWipeEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_WIPE_ENABLED, false)

    val autoWipeThreshold: Int
        get() = prefs.getInt(KEY_AUTO_WIPE_THRESHOLD, DEFAULT_WIPE_THRESHOLD)

    fun setAutoWipeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_WIPE_ENABLED, enabled).apply()
    }

    fun setAutoWipeThreshold(threshold: Int) {
        prefs.edit().putInt(KEY_AUTO_WIPE_THRESHOLD, threshold).apply()
    }

    fun wipeAll() {
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                AppLogger.log(context, "[auth]", "Auto-wipe triggered — deleting all vault data")

                // Secure-delete all vault files and thumbnails
                val files = vaultRepository.getAllFiles().first()
                for (vaultFile in files) {
                    runCatching {
                        vaultFile.encryptedFilePath?.let { encryptionService.secureDelete(File(it)) }
                        vaultFile.thumbnailPath?.let { encryptionService.secureDelete(File(it)) }
                    }
                }

                // Delete recordings directory
                val recordingsDir = File(context.filesDir, "recordings")
                recordingsDir.listFiles()?.forEach { file ->
                    runCatching { encryptionService.secureDelete(file) }
                }

                // Delete vault encrypted files directory
                val vaultDir = File(context.filesDir, "vault")
                vaultDir.listFiles()?.forEach { file ->
                    runCatching { encryptionService.secureDelete(file) }
                }

                // Delete notes and tasks DB by clearing the app data directory databases
                val dbDir = File(context.dataDir, "databases")
                dbDir.listFiles()?.forEach { file ->
                    runCatching { file.delete() }
                }

                // Clear all encrypted prefs (this wipes PIN, settings, etc.)
                prefs.edit().clear().apply()

                AppLogger.log(context, "[auth]", "Auto-wipe complete")
            }.onFailure { e ->
                AppLogger.log(context, "[auth]", "Auto-wipe error: ${e.message}")
            }
        }
    }
}
