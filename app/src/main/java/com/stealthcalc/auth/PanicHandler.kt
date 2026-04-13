package com.stealthcalc.auth

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * Detects shake gestures to trigger panic lock (instant return to calculator).
 * Also tracks rapid back-press (3x in 2 seconds).
 */
@Singleton
class PanicHandler @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private var onPanic: (() -> Unit)? = null

    private var lastShakeTime: Long = 0
    private val shakeThreshold = 25f // m/s²
    private val shakeCooldownMs = 1000L

    private val backPressTimes = mutableListOf<Long>()
    private val backPressWindowMs = 2000L
    private val backPressCount = 3

    private val shakeListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            event ?: return
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]

            // Remove gravity from the acceleration
            val acceleration = sqrt((x * x + y * y + z * z).toDouble()).toFloat()

            val now = System.currentTimeMillis()
            if (acceleration > shakeThreshold && now - lastShakeTime > shakeCooldownMs) {
                lastShakeTime = now
                onPanic?.invoke()
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    fun startListening(onPanicTriggered: () -> Unit) {
        onPanic = onPanicTriggered
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        accelerometer?.let {
            sensorManager?.registerListener(shakeListener, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stopListening() {
        sensorManager?.unregisterListener(shakeListener)
        onPanic = null
    }

    /**
     * Call this from onBackPressed. Returns true if panic should be triggered.
     */
    fun onBackPressed(): Boolean {
        val now = System.currentTimeMillis()
        backPressTimes.add(now)
        // Remove old presses outside the window
        backPressTimes.removeAll { now - it > backPressWindowMs }
        return if (backPressTimes.size >= backPressCount) {
            backPressTimes.clear()
            onPanic?.invoke()
            true
        } else {
            false
        }
    }
}
