package com.stealthcalc.recorder.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Size
import android.view.Surface
import androidx.core.app.NotificationCompat
import com.stealthcalc.MainActivity
import com.stealthcalc.recorder.model.CameraFacing
import com.stealthcalc.recorder.model.Recording
import com.stealthcalc.recorder.model.RecordingType
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@AndroidEntryPoint
class RecorderService : Service() {

    companion object {
        const val ACTION_START_AUDIO = "com.stealthcalc.START_AUDIO"
        const val ACTION_START_VIDEO = "com.stealthcalc.START_VIDEO"
        const val ACTION_STOP = "com.stealthcalc.STOP"
        const val EXTRA_CAMERA_FACING = "camera_facing"
        const val EXTRA_MAX_DURATION_MS = "max_duration_ms"

        private const val NOTIFICATION_ID = 9001
        private const val CHANNEL_ID = "calc_channel"

        // Shared state for UI to observe
        private val _isRecording = MutableStateFlow(false)
        val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

        private val _recordingType = MutableStateFlow<RecordingType?>(null)
        val recordingType: StateFlow<RecordingType?> = _recordingType.asStateFlow()

        private val _elapsedMs = MutableStateFlow(0L)
        val elapsedMs: StateFlow<Long> = _elapsedMs.asStateFlow()

        private val _currentRecordingId = MutableStateFlow<String?>(null)
        val currentRecordingId: StateFlow<String?> = _currentRecordingId.asStateFlow()

        // Completed recording info for the UI to pick up
        private val _lastCompletedRecording = MutableStateFlow<RecordingResult?>(null)
        val lastCompletedRecording: StateFlow<RecordingResult?> = _lastCompletedRecording.asStateFlow()

        fun clearLastCompleted() { _lastCompletedRecording.value = null }
    }

    private var mediaRecorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var recordingId: String? = null
    private var startTimeMs: Long = 0
    private var timerJob: Job? = null
    private var maxDurationMs: Long = 4 * 60 * 60 * 1000L // 4 hours default
    private var currentType: RecordingType = RecordingType.AUDIO
    private var currentFacing: CameraFacing = CameraFacing.BACK
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_AUDIO -> {
                maxDurationMs = intent.getLongExtra(EXTRA_MAX_DURATION_MS, 4 * 60 * 60 * 1000L)
                startRecording(RecordingType.AUDIO, null)
            }
            ACTION_START_VIDEO -> {
                val facingStr = intent.getStringExtra(EXTRA_CAMERA_FACING) ?: CameraFacing.BACK.name
                currentFacing = CameraFacing.valueOf(facingStr)
                maxDurationMs = intent.getLongExtra(EXTRA_MAX_DURATION_MS, 1 * 60 * 60 * 1000L)
                startRecording(RecordingType.VIDEO, currentFacing)
            }
            ACTION_STOP -> {
                stopRecording()
            }
        }
        return START_NOT_STICKY
    }

    private fun startRecording(type: RecordingType, facing: CameraFacing?) {
        if (_isRecording.value) return

        currentType = type
        recordingId = UUID.randomUUID().toString()

        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())
        val ext = if (type == RecordingType.AUDIO) "m4a" else "mp4"
        val fileName = "rec_${timestamp}.$ext"
        outputFile = File(filesDir, "recordings/$fileName").also {
            it.parentFile?.mkdirs()
        }

        try {
            mediaRecorder = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }).apply {
                if (type == RecordingType.VIDEO) {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setVideoSource(MediaRecorder.VideoSource.CAMERA)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioEncodingBitRate(128_000)
                    setAudioSamplingRate(44_100)
                    setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                    setVideoEncodingBitRate(2_000_000)
                    setVideoFrameRate(30)
                    setVideoSize(1280, 720)
                    if (maxDurationMs > 0) setMaxDuration(maxDurationMs.toInt())
                } else {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioEncodingBitRate(128_000)
                    setAudioSamplingRate(44_100)
                    if (maxDurationMs > 0) setMaxDuration(maxDurationMs.toInt())
                }
                setOutputFile(outputFile!!.absolutePath)
                setOnInfoListener { _, what, _ ->
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                        stopRecording()
                    }
                }
                prepare()
                start()
            }

            startTimeMs = System.currentTimeMillis()
            _isRecording.value = true
            _recordingType.value = type
            _currentRecordingId.value = recordingId
            _elapsedMs.value = 0

            // Start elapsed timer
            timerJob = serviceScope.launch {
                while (isActive && _isRecording.value) {
                    _elapsedMs.value = System.currentTimeMillis() - startTimeMs
                    delay(500)
                }
            }

            // Show covert notification
            startForeground(NOTIFICATION_ID, createNotification())

        } catch (e: Exception) {
            cleanupRecorder()
            _isRecording.value = false
        }
    }

    private fun stopRecording() {
        if (!_isRecording.value) return

        val duration = System.currentTimeMillis() - startTimeMs
        val file = outputFile
        val id = recordingId
        val type = currentType

        try {
            mediaRecorder?.stop()
        } catch (_: Exception) { }
        cleanupRecorder()

        timerJob?.cancel()
        _isRecording.value = false
        _recordingType.value = null
        _currentRecordingId.value = null
        _elapsedMs.value = 0

        // Report completed recording
        if (file != null && file.exists() && id != null) {
            val timestamp = SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault()).format(Date(startTimeMs))
            val typeLabel = if (type == RecordingType.VIDEO) "Video" else "Recording"
            _lastCompletedRecording.value = RecordingResult(
                recording = Recording(
                    id = id,
                    title = "$typeLabel $timestamp",
                    encryptedFilePath = file.absolutePath,
                    type = type,
                    durationMs = duration,
                    fileSizeBytes = file.length(),
                    format = if (type == RecordingType.VIDEO) "mp4" else "m4a",
                    cameraFacing = if (type == RecordingType.VIDEO) currentFacing else null,
                )
            )
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cleanupRecorder() {
        try {
            mediaRecorder?.release()
        } catch (_: Exception) { }
        mediaRecorder = null
    }

    private fun createNotification(): Notification {
        createNotificationChannel()

        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Covert notification — looks like a calculator notification
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Calculator")
            .setContentText("Calculation in progress...")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Calculator",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Calculator notifications"
            setShowBadge(false)
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        stopRecording()
        super.onDestroy()
    }
}

data class RecordingResult(
    val recording: Recording
)
