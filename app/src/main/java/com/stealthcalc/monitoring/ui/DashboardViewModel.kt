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
import javax.inject.Inject

data class DashboardState(
    val isPaired: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    val deviceState: DeviceState? = null,
    val recentEvents: List<EventPayload> = emptyList(),
    val lastRefresh: Long = 0L,
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val repository: MonitoringRepository,
    private val apiClient: AgentApiClient,
) : ViewModel() {

    private val _state = MutableStateFlow(
        DashboardState(isPaired = repository.isPaired)
    )
    val state: StateFlow<DashboardState> = _state.asStateFlow()

    init {
        if (repository.isDashboard && repository.isPaired) {
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
            if (deviceState != null) {
                _state.update {
                    it.copy(
                        isLoading = false,
                        deviceState = deviceState,
                        recentEvents = events,
                        lastRefresh = System.currentTimeMillis(),
                    )
                }
            } else {
                _state.update {
                    it.copy(
                        isLoading = false,
                        error = "Could not reach server",
                        recentEvents = events,
                    )
                }
            }
        }
    }
}
