package com.stealthcalc.monitoring.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stealthcalc.monitoring.data.MonitoringRepository
import com.stealthcalc.monitoring.model.LocationPayload
import com.stealthcalc.monitoring.model.MonitoringEventKind
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import javax.inject.Inject

data class LocationPoint(
    val latitude: Double,
    val longitude: Double,
    val timestampMs: Long,
)

data class LocationMapState(
    val points: List<LocationPoint> = emptyList(),
    val isLoading: Boolean = true,
)

@HiltViewModel
class LocationMapViewModel @Inject constructor(
    private val repository: MonitoringRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(LocationMapState())
    val state: StateFlow<LocationMapState> = _state.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true }

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true)
            val events = repository.getRecentByKind(MonitoringEventKind.LOCATION.name, limit = 200)
            val points = events.mapNotNull { event ->
                runCatching {
                    val payload = json.decodeFromString<LocationPayload>(event.payload)
                    LocationPoint(payload.latitude, payload.longitude, event.capturedAt)
                }.getOrNull()
            }.sortedBy { it.timestampMs }
            _state.value = LocationMapState(points = points, isLoading = false)
        }
    }
}
