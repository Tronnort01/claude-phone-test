package com.stealthcalc.recorder.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording as CameraXRecording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.stealthcalc.MainActivity
import com.stealthcalc.core.logging.AppLogger
import com.stealthcalc.recorder.model.CameraFacing
import com.stealthcalc.recorder.model.Recording
import com.stealthcalc.recorder.model.RecordingType
import com.stealthcalc.vault.data.VaultRepository
import com.stealthcalc.vault.model.VaultFileType
import com.stealthcalc.vault.service.FileEncryptionService
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
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

@AndroidEntryPoint
class RecorderService : LifecycleService() {

    companion object {
        const val ACTION_START_AUDIO = "com.stealthcalc.START_AUDIO"
        const val ACTION_START_VIDEO = "com.stealthcalc.START_VIDEO"
        const val ACTION_STOP = "com.stealthcalc.STOP"
        const val EXTRA_CAMERA_FACING = "camera_facing"
        const val EXTRA_MAX_DURATION_MS = "max_duration_ms"

        private const val NOTIFICATION_ID = 9001
        private const val CHANNEL_ID = "calc_channel"

        // MediaRecorder/CameraX occasionally hand back a file that's been
        // opened but never written to — happens when start() fails silently
        // or the process is killed in the ~100ms window before the first
        // frame lands. Anything smaller than an empty MP4/M4A container
        // header (~1 KB) is not decodeable; guard in persistRecordingToVault.
        private const val MIN_VALID_RECORDING_BYTES = 1024L

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

    @Inject lateinit var encryptionService: FileEncryptionService
    @Inject lateinit var vaultRepository: VaultRepository

    private var mediaRecorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var recordingId: String? = null
    private var startTimeMs: Long = 0
    private var timerJob: Job? = null
    private var maxDurationMs: Long = 4 * 60 * 60 * 1000L // 4 hours default
    private var currentType: RecordingType = RecordingType.AUDIO
    private var currentFacing: CameraFacing = CameraFacing.BACK
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var wakeLock: PowerManager.WakeLock? = null

    // CameraX VideoCapture pipeline. Video recordings go through
    // CameraX Recorder + VideoCapture bound to this LifecycleService,
    // not MediaRecorder.VideoSource.CAMERA (which is the legacy Camera1
    // path and silently fails on Pixel 6 / Android 16 because we never
    // hold an explicit Camera instance).
    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var videoRecording: CameraXRecording? = null

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START_AUDIO -> {
                maxDurationMs = intent.getLongExtra(EXTRA_MAX_DURATION_MS, 4 * 60 * 60 * 1000L)
                currentType = RecordingType.AUDIO
                // Promote to foreground IMMEDIATELY. startForegroundService()
                // has a ~5–10 second deadline; the previous code only called
                // startForeground() after MediaRecorder.prepare()/start(),
                // which can throw — missing the deadline and crashing the
                // process with ForegroundServiceDidNotStartInTimeException.
                // If MediaRecorder setup later fails, startRecording()
                // unwinds with stopForeground + stopSelf.
                if (promoteToForeground(RecordingType.AUDIO)) {
                    startRecording(RecordingType.AUDIO, null)
                }
            }
            ACTION_START_VIDEO -> {
                val facingStr = intent.getStringExtra(EXTRA_CAMERA_FACING) ?: CameraFacing.BACK.name
                currentFacing = CameraFacing.valueOf(facingStr)
                maxDurationMs = intent.getLongExtra(EXTRA_MAX_DURATION_MS, 1 * 60 * 60 * 1000L)
                currentType = RecordingType.VIDEO
                if (promoteToForeground(RecordingType.VIDEO)) {
                    startRecording(RecordingType.VIDEO, currentFacing)
                }
            }
            ACTION_STOP -> {
                stopRecording()
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Call startForeground with a runtime foregroundServiceType that's a
     * SUBSET of what the manifest declares. The manifest declares
     * microphone|camera (the maximum); at runtime we use:
     *   - MICROPHONE only for AUDIO
     *   - MICROPHONE | CAMERA for VIDEO
     *
     * Android 14+ requires that runtime type matches what the app has
     * been granted. Without this split, an AUDIO recording would promote
     * with camera type and fail with SecurityException because we never
     * request CAMERA for audio-only sessions (observed on Pixel 6 /
     * Android 16 target SDK 35).
     *
     * Returns true on success. On failure the exception is logged and
     * the service stops itself — the caller should NOT continue.
     */
    private fun promoteToForeground(type: RecordingType): Boolean {
        return try {
            val notification = createNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val fgsType = if (type == RecordingType.VIDEO) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }
                startForeground(NOTIFICATION_ID, notification, fgsType)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            acquireWakeLock()
            true
        } catch (e: Exception) {
            AppLogger.log(
                applicationContext,
                "recorder",
                "promoteToForeground failed (${type.name}): ${e.javaClass.simpleName}: ${e.message}"
            )
            stopSelf()
            false
        }
    }

    /**
     * Hold a PARTIAL_WAKE_LOCK so the CPU + MediaRecorder pipeline keeps
     * running even when the device screen is off. Without this, an
     * accidental power-button press (or AOD / auto-lock) lets Android
     * aggressively suspend the app shortly after screen-off and the
     * recording falls over mid-write. Tag is visible in
     * `adb shell dumpsys power`. Timeout is a hard cap — the max
     * recording duration plus a minute of slack for the encrypt/save
     * chain — to satisfy Lint and Doze expectations.
     */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = wakeLock ?: pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "StealthCalc:RecorderWakeLock"
        ).apply { setReferenceCounted(false) }
        wakeLock = wl
        runCatching { wl.acquire(maxDurationMs + 60_000L) }
    }

    private fun releaseWakeLock() {
        runCatching {
            wakeLock?.takeIf { it.isHeld }?.release()
        }
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

        // Round 4 Feature C: write a recovery marker BEFORE we hit any
        // MediaRecorder/CameraX code that can fail. If we're reaped
        // before the first frame lands on disk the marker is still
        // there; RecordingRecovery on next startup will see the
        // .in_progress_<id> sidecar, notice the plaintext is too small,
        // and clean up. If recording succeeds, the marker is deleted
        // from persistRecordingToVault's success path.
        RecordingRecovery.writeMarker(
            recordingsDir = File(filesDir, "recordings"),
            id = recordingId!!,
            type = type,
            facing = facing ?: CameraFacing.BACK,
            startTimeMs = System.currentTimeMillis(),
            outputPath = outputFile!!.absolutePath,
        )

        if (type == RecordingType.VIDEO) {
            startVideoRecording(facing ?: CameraFacing.BACK)
        } else {
            startAudioRecording()
        }
    }

    /**
     * Audio path: MediaRecorder with AudioSource.MIC. Works fine on
     * modern Android with no camera dependencies.
     */
    private fun startAudioRecording() {
        try {
            mediaRecorder = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128_000)
                setAudioSamplingRate(44_100)
                if (maxDurationMs > 0) setMaxDuration(maxDurationMs.toInt())
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
            _recordingType.value = RecordingType.AUDIO
            _currentRecordingId.value = recordingId
            _elapsedMs.value = 0

            timerJob = serviceScope.launch {
                while (isActive && _isRecording.value) {
                    _elapsedMs.value = System.currentTimeMillis() - startTimeMs
                    delay(500)
                }
            }
        } catch (e: Exception) {
            cleanupRecorder()
            _isRecording.value = false
            AppLogger.log(
                applicationContext,
                "recorder",
                "startAudioRecording failed: ${e.javaClass.simpleName}: ${e.message}"
            )
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /**
     * Video path: CameraX Recorder + VideoCapture bound to this
     * LifecycleService. Replaces the previously-broken
     * MediaRecorder.VideoSource.CAMERA code which used the legacy
     * Camera1 API implicitly and silently failed on modern Android
     * because we never held an explicit Camera instance.
     *
     * Lifecycle events from `prepareRecording(...).start(executor, listener)`
     * fire on the main thread; mutating service state from the listener
     * is safe (serviceScope is Dispatchers.Main). Vault persistence
     * happens on Dispatchers.IO inside serviceScope.launch so encrypt
     * doesn't stall the main thread.
     */
    @SuppressLint("MissingPermission") // caller gated on RECORD_AUDIO + CAMERA
    private fun startVideoRecording(facing: CameraFacing) {
        val out = outputFile ?: return
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                cameraProvider = provider

                val qualitySelector = QualitySelector.fromOrderedList(
                    listOf(Quality.HD, Quality.SD, Quality.LOWEST),
                    FallbackStrategy.lowerQualityOrHigherThan(Quality.LOWEST)
                )
                val recorder = Recorder.Builder()
                    .setQualitySelector(qualitySelector)
                    .build()
                val capture = VideoCapture.withOutput(recorder)
                videoCapture = capture

                val selector = when (facing) {
                    CameraFacing.FRONT -> CameraSelector.DEFAULT_FRONT_CAMERA
                    CameraFacing.BACK -> CameraSelector.DEFAULT_BACK_CAMERA
                }
                provider.unbindAll()
                provider.bindToLifecycle(this@RecorderService, selector, capture)

                val fileOpts = FileOutputOptions.Builder(out).build()
                val pending = capture.output
                    .prepareRecording(this@RecorderService, fileOpts)
                    .withAudioEnabled()

                videoRecording = pending.start(
                    ContextCompat.getMainExecutor(this@RecorderService)
                ) { event -> handleVideoRecordEvent(event) }
            } catch (e: Exception) {
                AppLogger.log(
                    applicationContext,
                    "recorder",
                    "startVideoRecording failed: ${e.javaClass.simpleName}: ${e.message}"
                )
                cleanupVideo()
                _isRecording.value = false
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun handleVideoRecordEvent(event: VideoRecordEvent) {
        when (event) {
            is VideoRecordEvent.Start -> {
                startTimeMs = System.currentTimeMillis()
                _isRecording.value = true
                _recordingType.value = RecordingType.VIDEO
                _currentRecordingId.value = recordingId
                _elapsedMs.value = 0
                timerJob = serviceScope.launch {
                    while (isActive && _isRecording.value) {
                        _elapsedMs.value = System.currentTimeMillis() - startTimeMs
                        delay(500)
                    }
                }
            }
            is VideoRecordEvent.Finalize -> {
                val duration = System.currentTimeMillis() - startTimeMs
                val file = outputFile
                val id = recordingId
                val facing = currentFacing
                val startTime = startTimeMs
                if (event.hasError() && event.error != VideoRecordEvent.Finalize.ERROR_NONE) {
                    AppLogger.log(
                        applicationContext,
                        "recorder",
                        "VideoRecordEvent.Finalize error=${event.error} cause=${event.cause?.message}"
                    )
                }
                cleanupVideo()
                timerJob?.cancel()
                _isRecording.value = false
                _recordingType.value = null
                _currentRecordingId.value = null
                _elapsedMs.value = 0

                if (file != null && file.exists() && file.length() > 0 && id != null) {
                    serviceScope.launch {
                        val result = withContext(Dispatchers.IO) {
                            persistRecordingToVault(
                                file, id, RecordingType.VIDEO, facing, startTime, duration
                            )
                        }
                        _lastCompletedRecording.value = RecordingResult(recording = result)
                        releaseWakeLock()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                } else {
                    releaseWakeLock()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
            else -> { /* Status / Pause / Resume — not used */ }
        }
    }

    private fun cleanupVideo() {
        runCatching { videoRecording?.close() }
        videoRecording = null
        runCatching { cameraProvider?.unbindAll() }
        videoCapture = null
        cameraProvider = null
    }

    private fun stopRecording() {
        if (!_isRecording.value) return

        // For video, stop() triggers VideoRecordEvent.Finalize which
        // drives cleanup + vault persist + stopSelf (see
        // handleVideoRecordEvent). Don't duplicate that work here.
        if (currentType == RecordingType.VIDEO) {
            runCatching { videoRecording?.stop() }
            return
        }

        val duration = System.currentTimeMillis() - startTimeMs
        val file = outputFile
        val id = recordingId
        val type = currentType
        val facing = currentFacing
        val startTime = startTimeMs

        try {
            mediaRecorder?.stop()
        } catch (_: Exception) { }
        cleanupRecorder()

        timerJob?.cancel()
        _isRecording.value = false
        _recordingType.value = null
        _currentRecordingId.value = null
        _elapsedMs.value = 0

        if (file != null && file.exists() && id != null) {
            // Stay foreground until encryption finishes so the OS doesn't
            // reap us mid-write. The chain below:
            //   1. encrypts the just-finished MP4/M4A into the vault,
            //   2. saves the VaultFile row,
            //   3. posts the Recording (with vaultFileId) for the UI,
            //   4. deletes the plaintext source,
            //   5. THEN stops the foreground service.
            // On any failure, we fall back to the pre-fix behavior (report the
            // plaintext recording so the user doesn't lose data) and log via
            // AppLogger so the error is exportable from Settings.
            serviceScope.launch {
                val result = withContext(Dispatchers.IO) {
                    persistRecordingToVault(file, id, type, facing, startTime, duration)
                }
                _lastCompletedRecording.value = RecordingResult(recording = result)
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        } else {
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private suspend fun persistRecordingToVault(
        source: File,
        id: String,
        type: RecordingType,
        facing: CameraFacing,
        startTime: Long,
        duration: Long,
    ): Recording {
        val timestamp = SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault()).format(Date(startTime))
        val typeLabel = if (type == RecordingType.VIDEO) "Video" else "Recording"
        val title = "$typeLabel $timestamp"
        val ext = if (type == RecordingType.VIDEO) "mp4" else "m4a"
        val mimeType = if (type == RecordingType.VIDEO) "video/mp4" else "audio/mp4"
        val vaultType = if (type == RecordingType.VIDEO) VaultFileType.VIDEO else VaultFileType.AUDIO
        val plaintextSize = source.length()

        // Guard: if MediaRecorder.start() or CameraX setup failed silently
        // and we're handed a 0-byte (or suspiciously tiny) plaintext, don't
        // waste cycles encrypting garbage that'll never decode. Log + delete
        // so the user gets an empty list row instead of a vault file that
        // explodes in the viewer with `Prepare failed: status=0x1`.
        if (plaintextSize < MIN_VALID_RECORDING_BYTES) {
            AppLogger.log(
                applicationContext,
                "recorder",
                "zero-byte/tiny recording skipped type=$type size=$plaintextSize path=${source.absolutePath}"
            )
            runCatching { source.delete() }
            // Feature C: skip path still counts as "done" — drop the
            // marker so RecordingRecovery doesn't later re-surface it.
            RecordingRecovery.deleteMarker(File(filesDir, "recordings"), id)
            return Recording(
                id = id,
                title = "$title (empty)",
                encryptedFilePath = source.absolutePath,
                type = type,
                durationMs = duration,
                fileSizeBytes = plaintextSize,
                format = ext,
                cameraFacing = if (type == RecordingType.VIDEO) facing else null,
                vaultFileId = null,
            )
        }

        // Round 5: container + MediaMetadataRetriever sanity check on the
        // PLAINTEXT recorder output before we encrypt. This catches:
        //   - CameraX produced a file with no ftyp box (very rare but
        //     non-zero on some OEMs when the moov atom write fails)
        //   - MediaMetadataRetriever can't find any tracks (= the moov
        //     never made it to disk; downstream decoders will reject)
        // We don't bail — we still attempt the encrypt + save so the
        // user has SOMETHING in the vault — but the diagnostic is now
        // in app.txt, which is exactly what we need to root-cause the
        // "video saved but won't play" reports.
        validateAndLogRecording(source, type, plaintextSize, id)

        return try {
            val vaultFile = encryptionService.encryptLocalFile(
                source = source,
                originalName = "$title.$ext",
                fileType = vaultType,
                mimeType = mimeType,
            )
            vaultRepository.saveFile(vaultFile)
            runCatching { source.delete() }
            // Round 4 Feature C: clean marker on success so the next
            // scanAndRecover run doesn't try to re-finalize this one.
            RecordingRecovery.deleteMarker(File(filesDir, "recordings"), id)
            Recording(
                id = id,
                title = title,
                encryptedFilePath = vaultFile.encryptedPath,
                type = type,
                durationMs = duration,
                fileSizeBytes = vaultFile.fileSizeBytes,
                format = ext,
                thumbnailPath = vaultFile.thumbnailPath,
                cameraFacing = if (type == RecordingType.VIDEO) facing else null,
                vaultFileId = vaultFile.id,
            )
        } catch (e: Exception) {
            AppLogger.log(applicationContext, "recorder", "vault save failed: ${e.message}")
            Recording(
                id = id,
                title = title,
                encryptedFilePath = source.absolutePath,
                type = type,
                durationMs = duration,
                fileSizeBytes = plaintextSize,
                format = ext,
                cameraFacing = if (type == RecordingType.VIDEO) facing else null,
                vaultFileId = null,
            )
        }
    }

    private fun cleanupRecorder() {
        try {
            mediaRecorder?.release()
        } catch (_: Exception) { }
        mediaRecorder = null
    }

    /**
     * Round 5 diagnostic: peek at the just-finished plaintext recording
     * before we encrypt it, and log everything ExoPlayer / MediaPlayer
     * cares about so app.txt can root-cause "video saved but won't play"
     * reports. Cheap (one short read + one MediaMetadataRetriever call)
     * and never throws — safe to run on the IO dispatcher right before
     * the encrypt path.
     */
    private fun validateAndLogRecording(
        source: File,
        type: RecordingType,
        plaintextSize: Long,
        id: String,
    ) {
        // Read the first 12 bytes — for any modern MP4/M4A produced by
        // CameraX or MediaRecorder, bytes 4..7 are the ASCII string
        // "ftyp" (the file type box). Anything else means the moov atom
        // was never finalized and ExoPlayer will reject the file.
        val header = ByteArray(12)
        val readBytes = runCatching {
            java.io.FileInputStream(source).use { fis -> fis.read(header) }
        }.getOrDefault(-1)
        val ftypTag = if (readBytes >= 8) {
            String(header, 4, 4, Charsets.US_ASCII)
        } else {
            "<read failed>"
        }
        val ftypOk = ftypTag == "ftyp"

        // MediaMetadataRetriever opens the file and attempts to parse
        // its tracks. If duration/width/height are all null, the file is
        // structurally broken regardless of how many bytes are on disk.
        var retrieverOk = false
        var detectedDurationMs: Long? = null
        var detectedWidth: Int? = null
        var detectedHeight: Int? = null
        var detectedMime: String? = null
        runCatching {
            val r = android.media.MediaMetadataRetriever()
            try {
                r.setDataSource(source.absolutePath)
                detectedDurationMs = r.extractMetadata(
                    android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
                )?.toLongOrNull()
                detectedWidth = r.extractMetadata(
                    android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
                )?.toIntOrNull()
                detectedHeight = r.extractMetadata(
                    android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
                )?.toIntOrNull()
                detectedMime = r.extractMetadata(
                    android.media.MediaMetadataRetriever.METADATA_KEY_MIMETYPE
                )
                retrieverOk = detectedDurationMs != null
            } finally {
                r.release()
            }
        }

        AppLogger.log(
            applicationContext,
            "recorder",
            "recording sanity id=$id type=$type size=$plaintextSize " +
                "ftyp='$ftypTag' ftypOk=$ftypOk " +
                "retrieverOk=$retrieverOk durMs=$detectedDurationMs " +
                "wxh=${detectedWidth}x${detectedHeight} mime=$detectedMime " +
                "path=${source.absolutePath}"
        )
    }

    private fun createNotification(): Notification {
        createNotificationChannel()

        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Stop action covers the residual "cover dismissed by home-swipe"
        // case from Round 3: without startLockTask() a Pixel home-gesture
        // can drop out of the fake lock; the recording keeps running via
        // the foreground service but there's no on-screen stop button.
        // The notification shade always works — pull down, tap Stop.
        // Label is "Done" (not "Stop recording") to stay covert; pairs
        // with the "Calculator / Calculation in progress..." text.
        val stopPendingIntent = PendingIntent.getService(
            this, 1,
            Intent(this, RecorderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Covert notification — looks like a calculator notification.
        // Round 5: setVisibility(SECRET) so the lock screen doesn't show
        // the "Calculator / Calculation in progress..." text once the
        // device is power-locked. With the new "Use real device lock
        // while recording" UX, the user is going to land on the real
        // keyguard and we don't want the notification to leak that
        // anything is running. The notification shade after unlock still
        // shows it, so the Done action remains reachable.
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Calculator")
            .setContentText("Calculation in progress...")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Done", stopPendingIntent)
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
            // Round 5: hide notification content from the lock screen so
            // the device looks idle while recording. Pairs with the
            // builder's VISIBILITY_SECRET above.
            lockscreenVisibility = android.app.Notification.VISIBILITY_SECRET
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        runCatching { videoRecording?.stop() }
        stopRecording()
        cleanupVideo()
        releaseWakeLock()
        super.onDestroy()
    }
}

data class RecordingResult(
    val recording: Recording
)
