package com.stealthcalc.monitoring.collector

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.stealthcalc.monitoring.data.MonitoringRepository
import com.stealthcalc.monitoring.model.MonitoringEventKind
import com.stealthcalc.monitoring.model.SecurityEventPayload
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContactChangeCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: MonitoringRepository,
) {
    private val scope = CoroutineScope(Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())
    private var observer: ContentObserver? = null
    private var lastContactCount: Int = -1

    fun start() {
        if (!repository.isMetricEnabled("contact_changes")) return
        if (observer != null) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED) return

        lastContactCount = getContactCount()

        observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                scope.launch { checkChanges() }
            }
        }
        context.contentResolver.registerContentObserver(
            ContactsContract.Contacts.CONTENT_URI, true, observer!!
        )
    }

    fun stop() {
        observer?.let { context.contentResolver.unregisterContentObserver(it) }
        observer = null
    }

    private suspend fun checkChanges() {
        val currentCount = getContactCount()
        if (lastContactCount >= 0 && currentCount != lastContactCount) {
            val diff = currentCount - lastContactCount
            val event = if (diff > 0) "CONTACT_ADDED" else "CONTACT_REMOVED"
            val details = "Contact count changed: $lastContactCount → $currentCount (${if (diff > 0) "+$diff" else "$diff"})"

            val payload = Json.encodeToString(
                SecurityEventPayload(
                    event = event,
                    details = details,
                    timestampMs = System.currentTimeMillis(),
                )
            )
            repository.recordEvent(MonitoringEventKind.SECURITY_EVENT, payload)
        }
        lastContactCount = currentCount
    }

    private fun getContactCount(): Int {
        return runCatching {
            context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf("COUNT(*)"),
                null, null, null
            )?.use { if (it.moveToFirst()) it.getInt(0) else 0 } ?: 0
        }.getOrDefault(0)
    }
}
