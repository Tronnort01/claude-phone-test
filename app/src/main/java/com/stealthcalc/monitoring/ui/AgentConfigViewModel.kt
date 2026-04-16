package com.stealthcalc.monitoring.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stealthcalc.core.logging.AppLogger
import com.stealthcalc.monitoring.data.MonitoringRepository
import com.stealthcalc.monitoring.network.AgentApiClient
import com.stealthcalc.monitoring.service.AgentService
import com.stealthcalc.monitoring.service.AgentSyncWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AgentConfigState(
    val role: String = MonitoringRepository.ROLE_DISABLED,
    val serverUrl: String = "",
    val deviceName: String = "",
    val isPaired: Boolean = false,
    val deviceId: String = "",
    val isServiceRunning: Boolean = false,
    val unsentCount: Int = 0,
    val lastSync: Long = 0L,
    val enabledMetrics: Set<String> = MonitoringRepository.ALL_METRICS,
    val pairingInProgress: Boolean = false,
    val pairingError: String? = null,
    val showOtpDialog: Boolean = false,
)

@HiltViewModel
class AgentConfigViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: MonitoringRepository,
    private val apiClient: AgentApiClient,
) : ViewModel() {

    private val _state = MutableStateFlow(
        AgentConfigState(
            role = repository.role,
            serverUrl = repository.serverUrl,
            deviceName = repository.deviceName,
            isPaired = repository.isPaired,
            deviceId = repository.deviceId,
            isServiceRunning = AgentService.isRunning,
            lastSync = repository.lastSync,
            enabledMetrics = repository.enabledMetrics,
        )
    )
    val state: StateFlow<AgentConfigState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repository.unsentCount().collect { count ->
                _state.update { it.copy(unsentCount = count) }
            }
        }
    }

    fun setRole(role: String) {
        repository.setRole(role)
        _state.update { it.copy(role = role) }
        if (repository.isAgent && repository.isPaired) {
            AgentService.start(context)
            AgentSyncWorker.schedule(context)
        } else {
            AgentService.stop(context)
            AgentSyncWorker.cancel(context)
        }
    }

    fun setServerUrl(url: String) {
        repository.setServerUrl(url)
        _state.update { it.copy(serverUrl = url) }
    }

    fun setDeviceName(name: String) {
        repository.setDeviceName(name)
        _state.update { it.copy(deviceName = name) }
    }

    fun showOtpDialog() {
        _state.update { it.copy(showOtpDialog = true, pairingError = null) }
    }

    fun hideOtpDialog() {
        _state.update { it.copy(showOtpDialog = false, pairingError = null) }
    }

    fun submitOtp(otp: String) {
        if (otp.length < 6) {
            _state.update { it.copy(pairingError = "OTP must be 6 digits") }
            return
        }
        _state.update { it.copy(pairingInProgress = true, pairingError = null) }
        viewModelScope.launch {
            val name = repository.deviceName.ifBlank { android.os.Build.MODEL }
            val result = apiClient.pair(otp, name)
            if (result != null) {
                repository.savePairing(result.deviceId, result.token)
                _state.update {
                    it.copy(
                        isPaired = true,
                        deviceId = result.deviceId,
                        pairingInProgress = false,
                        showOtpDialog = false,
                    )
                }
                if (repository.isAgent) {
                    AgentService.start(context)
                    AgentSyncWorker.schedule(context)
                }
                AppLogger.log(context, "[agent]", "Paired as device ${result.deviceId}")
            } else {
                _state.update {
                    it.copy(pairingInProgress = false, pairingError = "Pairing failed — check OTP and server URL")
                }
            }
        }
    }

    fun unpair() {
        AgentService.stop(context)
        AgentSyncWorker.cancel(context)
        repository.clearPairing()
        _state.update { it.copy(isPaired = false, deviceId = "") }
    }

    fun toggleMetric(metric: String) {
        val current = repository.enabledMetrics.toMutableSet()
        if (metric in current) current.remove(metric) else current.add(metric)
        repository.setEnabledMetrics(current)
        _state.update { it.copy(enabledMetrics = current) }
    }

    fun startService() {
        if (repository.isAgent) {
            AgentService.start(context)
            AgentSyncWorker.schedule(context)
            _state.update { it.copy(isServiceRunning = true) }
        }
    }

    fun stopService() {
        AgentService.stop(context)
        _state.update { it.copy(isServiceRunning = false) }
    }
}
