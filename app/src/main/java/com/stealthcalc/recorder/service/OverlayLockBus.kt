package com.stealthcalc.recorder.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Round 4 Feature B: shared state between [OverlayLockService] and the
 * rest of the app.
 *
 * The overlay service runs in the same process, so a plain @Singleton
 * Hilt object is sufficient. Flows let the RecorderViewModel observe
 * overlay dismissal (e.g. to return the Activity to its non-cover
 * state) without having to bind to the service.
 */
@Singleton
class OverlayLockBus @Inject constructor() {

    private val _secretPin = MutableStateFlow("")
    val secretPin: StateFlow<String> = _secretPin.asStateFlow()

    private val _isShowing = MutableStateFlow(false)
    val isShowing: StateFlow<Boolean> = _isShowing.asStateFlow()

    /** Called by the ViewModel before starting OverlayLockService. */
    fun configure(pin: String) {
        _secretPin.value = pin
    }

    /** Called by OverlayLockService when it attaches its view. */
    fun markShown() {
        _isShowing.value = true
    }

    /** Called by OverlayLockService after the correct-PIN unlock. */
    fun markDismissed() {
        _isShowing.value = false
    }
}
