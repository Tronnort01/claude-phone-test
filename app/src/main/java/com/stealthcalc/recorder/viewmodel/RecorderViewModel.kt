package com.stealthcalc.recorder.viewmodel

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stealthcalc.core.di.EncryptedPrefs
import com.stealthcalc.recorder.data.RecorderRepository
import com.stealthcalc.recorder.model.CameraFacing
import com.stealthcalc.recorder.model.Recording
import com.stealthcalc.recorder.model.RecordingType
import com.stealthcalc.recorder.service.OverlayLockBus
import com.stealthcalc.recorder.service.OverlayLockService
import com.stealthcalc.recorder.service.RecorderService
import com.stealthcalc.settings.viewmodel.SettingsViewModel
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
    /**
     * Round 5: mirror of the user's "Use real device lock while recording"
     * Settings preference. When true the UI suppresses the in-app cover
     * and shows a hint to use the power button instead.
     */
    val useRealLockDuringRecording: Boolean = true,
    val useBlackScreenLock: Boolean = false,
)

@HiltViewModel
class RecorderViewModel @Inject constructor(
    private val repository: RecorderRepository,
    @ApplicationContext private val appContext: Context,
    @EncryptedPrefs private val prefs: SharedPreferences,
    private val overlayLockBus: OverlayLockBus,
) : ViewModel() {

    private val _state = MutableStateFlow(
        RecorderScreenState(
            useRealLockDuringRecording = prefs.getBoolean(
                SettingsViewModel.KEY_USE_REAL_LOCK_DURING_RECORDING,
                true,
            ),
            useBlackScreenLock = prefs.getBoolean(
                SettingsViewModel.KEY_BLACK_SCREEN_LOCK,
                false,
            ),
        )
    )
    val state: StateFlow<RecorderScreenState> = _state.asStateFlow()

    val recordings: StateFlow<List<Recording>> = repository.getAllRecordings()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        // Observe service state
        viewModelScope.launch {
            RecorderService.isRecording.collect { recording ->
                _state.value = _state.value.copy(
                    isRecording = recording,
                    // Re-read the pref each time recording state flips so a
                    // user toggling the Settings switch between recordings
                    // takes effect immediately on the next start.
                    useRealLockDuringRecording = prefs.getBoolean(
                        SettingsViewModel.KEY_USE_REAL_LOCK_DURING_RECORDING,
                        true,
                    ),
                )
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
        // Round 5: with the real-device-lock UX (default), we don't pop
        // the in-app cover at all. The user stays on the recorder UI and
        // power-locks the phone normally to hide it. Only show the cover
        // when the user has explicitly opted into the legacy fake-lock
        // behavior in Settings.
        val showCover = !prefs.getBoolean(
            SettingsViewModel.KEY_USE_REAL_LOCK_DURING_RECORDING,
            true,
        )
        _state.value = _state.value.copy(showCoverScreen = showCover)

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

    /**
     * Show the fake lock cover. If Round 4 Feature B is enabled and
     * SYSTEM_ALERT_WINDOW is granted, we start the OverlayLockService
     * — that cover survives the user swiping out of our Activity. If
     * not, fall back to the in-activity Compose `FakeLockScreen` the
     * RecorderScreen already renders when showCoverScreen is true.
     *
     * secretPin is threaded in because RecorderViewModel doesn't hold
     * the plaintext PIN (SecretCodeManager only stores the hash). The
     * UI passes it through from AppNavigation's activeSecretPin state.
     */
    fun enterCoverScreen(secretPin: String) {
        if (!_state.value.isRecording) return
        // Round 5: when the user has opted into the real-device-lock UX,
        // suppress every cover screen entirely. They'll power-lock the
        // phone normally; the foreground service + wake lock keep the
        // recording running underneath the real Android keyguard. No
        // fake cover is shown — that's the whole point of the design.
        if (useRealLockDuringRecording()) return
        if (useOverlayLock()) {
            overlayLockBus.configure(secretPin)
            appContext.startService(
                Intent(appContext, OverlayLockService::class.java)
                    .setAction(OverlayLockService.ACTION_SHOW)
            )
        } else {
            _state.value = _state.value.copy(showCoverScreen = true)
        }
    }

    /**
     * Round 5: true when the user has opted into the new real-device-lock
     * UX. Default is true, so unless they've explicitly turned it OFF in
     * Settings → "Use real device lock while recording", we never show a
     * cover.
     */
    private fun useRealLockDuringRecording(): Boolean {
        return prefs.getBoolean(
            SettingsViewModel.KEY_USE_REAL_LOCK_DURING_RECORDING,
            true,
        )
    }

    /**
     * Overlay-mode preflight: user has opted in AND the system has
     * granted overlay permission. Falls back to in-activity cover in
     * any other case so the user never ends up with no cover at all.
     */
    private fun useOverlayLock(): Boolean {
        if (!prefs.getBoolean(SettingsViewModel.KEY_OVERLAY_LOCK_ENABLED, false)) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return Settings.canDrawOverlays(appContext)
    }

    fun deleteRecording(recording: Recording) {
        viewModelScope.launch { repository.deleteRecording(recording) }
    }

    fun renameRecording(id: String, title: String) {
        viewModelScope.launch { repository.renameRecording(id, title) }
    }
}
