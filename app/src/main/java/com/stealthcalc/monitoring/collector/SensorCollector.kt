package com.stealthcalc.monitoring.collector

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.stealthcalc.monitoring.data.MonitoringRepository
import com.stealthcalc.monitoring.model.MonitoringEventKind
import com.stealthcalc.monitoring.model.SensorDataPayload
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

@Singleton
class SensorCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: MonitoringRepository,
) {
    private val scope = CoroutineScope(Dispatchers.IO)
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private var proximityListener: SensorEventListener? = null
    private var lightListener: SensorEventListener? = null
    private var accelListener: SensorEventListener? = null

    private var proximityNear: Boolean? = null
    private var lightLevel: Float? = null
    private var accelMagnitude: Float = 0f
    private var lastRecordTime: Long = 0L

    fun start() {
        if (!repository.isMetricEnabled("sensors")) return
        val sm = sensorManager ?: return

        sm.getDefaultSensor(Sensor.TYPE_PROXIMITY)?.let { sensor ->
            proximityListener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    proximityNear = event.values[0] < sensor.maximumRange
                    maybeRecord()
                }
                override fun onAccuracyChanged(s: Sensor, a: Int) {}
            }
            sm.registerListener(proximityListener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }

        sm.getDefaultSensor(Sensor.TYPE_LIGHT)?.let { sensor ->
            lightListener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    lightLevel = event.values[0]
                    maybeRecord()
                }
                override fun onAccuracyChanged(s: Sensor, a: Int) {}
            }
            sm.registerListener(lightListener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }

        sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sensor ->
            accelListener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    val x = event.values[0]
                    val y = event.values[1]
                    val z = event.values[2]
                    accelMagnitude = sqrt(x * x + y * y + z * z)
                    maybeRecord()
                }
                override fun onAccuracyChanged(s: Sensor, a: Int) {}
            }
            sm.registerListener(accelListener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    fun stop() {
        val sm = sensorManager ?: return
        proximityListener?.let { sm.unregisterListener(it) }
        lightListener?.let { sm.unregisterListener(it) }
        accelListener?.let { sm.unregisterListener(it) }
        proximityListener = null
        lightListener = null
        accelListener = null
    }

    private fun maybeRecord() {
        val now = System.currentTimeMillis()
        if (now - lastRecordTime < 5 * 60 * 1000L) return
        lastRecordTime = now

        val isMoving = accelMagnitude > 12f

        scope.launch {
            val payload = Json.encodeToString(
                SensorDataPayload(
                    proximityNear = proximityNear,
                    lightLevel = lightLevel,
                    isMoving = isMoving,
                    accelerometerMagnitude = accelMagnitude,
                    timestampMs = now,
                )
            )
            repository.recordEvent(MonitoringEventKind.SENSOR_DATA, payload)
        }
    }
}
