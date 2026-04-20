package com.stealthcalc.monitoring.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stealthcalc.monitoring.data.MonitoringRepository
import com.stealthcalc.monitoring.model.MonitoringEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

data class TimelineHour(
    val label: String,
    val events: List<MonitoringEvent>,
)

data class TimelineState(
    val hours: List<TimelineHour> = emptyList(),
    val isLoading: Boolean = true,
)

@HiltViewModel
class TimelineViewModel @Inject constructor(
    private val repository: MonitoringRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(TimelineState())
    val state: StateFlow<TimelineState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true)
            val events = repository.getRecent(500)
            val hourFmt = SimpleDateFormat("MMM d, HH:00", Locale.getDefault())
            val grouped = events
                .groupBy { hourFmt.format(Date(it.capturedAt)) }
                .map { (label, evts) -> TimelineHour(label, evts.sortedByDescending { it.capturedAt }) }
            _state.value = TimelineState(hours = grouped, isLoading = false)
        }
    }
}
