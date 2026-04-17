package com.stealthcalc.monitoring.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.stealthcalc.monitoring.model.EventPayload
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

object DataExportHelper {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun exportAsJson(context: Context, events: List<EventPayload>, fileName: String = "monitoring_export.json"): Boolean {
        return runCatching {
            val file = File(context.cacheDir, fileName)
            file.writeText(json.encodeToString(events))
            shareFile(context, file, "application/json")
            true
        }.getOrDefault(false)
    }

    fun exportAsCsv(context: Context, events: List<EventPayload>, fileName: String = "monitoring_export.csv"): Boolean {
        return runCatching {
            val file = File(context.cacheDir, fileName)
            file.bufferedWriter().use { writer ->
                writer.write("id,kind,captured_at,payload_summary")
                writer.newLine()
                for (event in events) {
                    val summary = extractSummary(event)
                    val escaped = summary.replace("\"", "\"\"")
                    writer.write("${event.id},${event.kind},${event.capturedAt},\"$escaped\"")
                    writer.newLine()
                }
            }
            shareFile(context, file, "text/csv")
            true
        }.getOrDefault(false)
    }

    private fun extractSummary(event: EventPayload): String {
        val obj = runCatching { json.parseToJsonElement(event.payload).jsonObject }.getOrNull()
            ?: return event.payload.take(200)
        return obj.entries.take(4).joinToString("; ") { (k, v) ->
            "$k=${v.jsonPrimitive.content.take(50)}"
        }
    }

    private fun shareFile(context: Context, file: File, mimeType: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Monitoring Data Export")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "Export data").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
