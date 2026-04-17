package com.stealthcalc.monitoring.collector

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.stealthcalc.monitoring.data.MonitoringRepository
import com.stealthcalc.monitoring.model.MonitoringEventKind
import com.stealthcalc.monitoring.model.StepCountPayload
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StepCountCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: MonitoringRepository,
) {
    private val scope = CoroutineScope(Dispatchers.IO)
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private var listener: SensorEventListener? = null
    private var lastStepCount: Float = -1f
    private var lastRecordTime: Long = 0L

    fun start() {
        if (!repository.isMetricEnabled("step_count")) return
        if (listener != null) return

        val stepSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return

        listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val currentSteps = event.values[0]
                val now = System.currentTimeMillis()

                if (lastStepCount < 0) {
                    lastStepCount = currentSteps
                    lastRecordTime = now
                    return
                }

                if (now - lastRecordTime >= 15 * 60 * 1000L) {
                    val delta = (currentSteps - lastStepCount).toInt()
                    val minutes = ((now - lastRecordTime) / 60_000).toInt()
                    if (delta > 0) {
                        scope.launch {
                            val payload = Json.encodeToString(
                                StepCountPayload(
                                    steps = delta,
                                    periodMinutes = minutes,
                                    timestampMs = now,
                                )
                            )
                            repository.recordEvent(MonitoringEventKind.STEP_COUNT, payload)
                        }
                    }
                    lastStepCount = currentSteps
                    lastRecordTime = now
                }
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
        }

        sensorManager.registerListener(listener, stepSensor, SensorManager.SENSOR_DELAY_NORMAL)
    }

    fun stop() {
        listener?.let { sensorManager?.unregisterListener(it) }
        listener = null
    }
}
