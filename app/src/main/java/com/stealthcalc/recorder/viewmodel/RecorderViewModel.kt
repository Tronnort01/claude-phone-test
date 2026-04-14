package com.stealthcalc.recorder.viewmodel

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stealthcalc.recorder.data.RecorderRepository
import com.stealthcalc.recorder.model.CameraFacing
import com.stealthcalc.recorder.model.Recording
import com.stealthcalc.recorder.model.RecordingType
import com.stealthcalc.recorder.service.RecorderService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RecorderScreenState(
    val selectedMode: RecordingType = RecordingType.AUDIO,
    val selectedCamera: CameraFacing = CameraFacing.BACK,
    val isRecording: Boolean = false,
    val elapsedMs: Long = 0,
    val showCoverScreen: Boolean = false,
)

@HiltViewModel
class RecorderViewModel @Inject constructor(
    private val repository: RecorderRepository,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    private val _state = MutableStateFlow(RecorderScreenState())
    val state: StateFlow<RecorderScreenState> = _state.asStateFlow()

    val recordings: StateFlow<List<Recording>> = repository.getAllRecordings()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        // Observe service state
        viewModelScope.launch {
            RecorderService.isRecording.collect { recording ->
                _state.value = _state.value.copy(isRecording = recording)
            }
        }
        viewModelScope.launch {
            RecorderService.elapsedMs.collect { elapsed ->
                _state.value = _state.value.copy(elapsedMs = elapsed)
            }
        }
        // Save completed recordings to DB
        viewModelScope.launch {
            RecorderService.lastCompletedRecording.collect { result ->
                if (result != null) {
                    repository.saveRecording(result.recording)
                    RecorderService.clearLastCompleted()
                }
            }
        }
    }

    fun selectMode(mode: RecordingType) {
        _state.value = _state.value.copy(selectedMode = mode)
    }

    fun selectCamera(facing: CameraFacing) {
        _state.value = _state.value.copy(selectedCamera = facing)
    }

    fun startRecording() {
        val s = _state.value
        val intent = Intent(appContext, RecorderService::class.java).apply {
            action = if (s.selectedMode == RecordingType.VIDEO) {
                RecorderService.ACTION_START_VIDEO
            } else {
                RecorderService.ACTION_START_AUDIO
            }
            putExtra(RecorderService.EXTRA_CAMERA_FACING, s.selectedCamera.name)
        }
        appContext.startForegroundService(intent)
        _state.value = _state.value.copy(showCoverScreen = true)

        // Safety net: if the service fails to enter the recording state
        // within a few seconds (e.g. a SecurityException on the
        // foreground-service promotion that we log but silently recover
        // from), dismiss the cover so the user isn't stuck staring at
        // the fake lock with no way to tell that nothing was captured.
        viewModelScope.launch {
            delay(4_000)
            if (!RecorderService.isRecording.value && _state.value.showCoverScreen) {
                _state.value = _state.value.copy(showCoverScreen = false)
            }
        }
    }

    fun stopRecording() {
        val intent = Intent(appContext, RecorderService::class.java).apply {
            action = RecorderService.ACTION_STOP
        }
        appContext.startService(intent)
        _state.value = _state.value.copy(showCoverScreen = false)
    }

    fun exitCoverScreen() {
        _state.value = _state.value.copy(showCoverScreen = false)
    }

    fun enterCoverScreen() {
        if (_state.value.isRecording) {
            _state.value = _state.value.copy(showCoverScreen = true)
        }
    }

    fun deleteRecording(recording: Recording) {
        viewModelScope.launch { repository.deleteRecording(recording) }
    }

    fun renameRecording(id: String, title: String) {
        viewModelScope.launch { repository.renameRecording(id, title) }
    }
}
