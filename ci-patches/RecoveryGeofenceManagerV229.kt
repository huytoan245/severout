package com.family.child

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices

/**
 * Secondary process-independent movement wake path.
 * Google Play services owns the geofence after registration, so an EXIT event
 * can return control to the app even if the app process was reclaimed.
 */
object RecoveryGeofenceManager {
    private const val PREFS = "tracking_diag"
    private const val REQUEST_ID = "family-location-recovery-v229"
    private const val RADIUS_M = 250f
    private const val MIN_REFRESH_MS = 3 * 60_000L
    private const val MAX_REFRESH_MS = 30 * 60_000L
    private const val RECENTER_DISTANCE_M = 100f
    private const val RESPONSIVENESS_MS = 30_000

    fun update(context: Context, location: Location) {
        if (!permissionsReady(context)) return
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()

        prefs.edit()
            .putLong("recovery_geofence_last_observed_at_v229", now)
            .putString("recovery_geofence_last_observed_lat_v229", location.latitude.toString())
            .putString("recovery_geofence_last_observed_lon_v229", location.longitude.toString())
            .apply()

        val registeredAt = prefs.getLong("recovery_geofence_registered_at_v229", 0L)
        val centerLat = prefs.getString("recovery_geofence_center_lat_v229", null)?.toDoubleOrNull()
        val centerLon = prefs.getString("recovery_geofence_center_lon_v229", null)?.toDoubleOrNull()
        val age = if (registeredAt > 0L) now - registeredAt else Long.MAX_VALUE
        val moved = if (centerLat != null && centerLon != null) {
            val out = FloatArray(1)
            Location.distanceBetween(centerLat, centerLon, location.latitude, location.longitude, out)
            out[0]
        } else Float.MAX_VALUE

        val shouldRefresh = registeredAt <= 0L ||
            age >= MAX_REFRESH_MS ||
            (age >= MIN_REFRESH_MS && moved >= RECENTER_DISTANCE_M)
        if (shouldRefresh) register(app, location.latitude, location.longitude, "gps")
    }

    fun rearmLatestObserved(context: Context, source: String = "rearm") {
        if (!permissionsReady(context)) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lat = prefs.getString("recovery_geofence_last_observed_lat_v229", null)?.toDoubleOrNull() ?: return
        val lon = prefs.getString("recovery_geofence_last_observed_lon_v229", null)?.toDoubleOrNull() ?: return
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return
        register(context.applicationContext, lat, lon, source)
    }

    private fun permissionsReady(context: Context): Boolean {
        val fine = ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val background = Build.VERSION.SDK_INT < 29 ||
            ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine && background
    }

    private fun pendingIntent(context: Context): PendingIntent {
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags = flags or PendingIntent.FLAG_MUTABLE
        return PendingIntent.getBroadcast(
            context,
            229,
            Intent(context, RecoveryGeofenceReceiver::class.java).setPackage(context.packageName),
            flags
        )
    }

    @Suppress("MissingPermission")
    private fun register(context: Context, lat: Double, lon: Double, source: String) {
        if (!permissionsReady(context)) return
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val geofence = Geofence.Builder()
            .setRequestId(REQUEST_ID)
            .setCircularRegion(lat, lon, RADIUS_M)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_EXIT)
            .setNotificationResponsiveness(RESPONSIVENESS_MS)
            .build()
        val request = GeofencingRequest.Builder()
            .setInitialTrigger(0)
            .addGeofence(geofence)
            .build()
        val pi = pendingIntent(app)
        val client = LocationServices.getGeofencingClient(app)

        try {
            client.removeGeofences(pi).addOnCompleteListener {
                try {
                    client.addGeofences(request, pi)
                        .addOnSuccessListener {
                            prefs.edit()
                                .putString("recovery_geofence_result_v229", "registered")
                                .putLong("recovery_geofence_registered_at_v229", System.currentTimeMillis())
                                .putString("recovery_geofence_center_lat_v229", lat.toString())
                                .putString("recovery_geofence_center_lon_v229", lon.toString())
                                .putString("recovery_geofence_source_v229", source)
                                .apply()
                        }
                        .addOnFailureListener { e ->
                            prefs.edit()
                                .putString("recovery_geofence_result_v229", "failed:${e.javaClass.simpleName}")
                                .putLong("recovery_geofence_failed_at_v229", System.currentTimeMillis())
                                .apply()
                        }
                } catch (e: Exception) {
                    prefs.edit()
                        .putString("recovery_geofence_result_v229", "add_failed:${e.javaClass.simpleName}")
                        .putLong("recovery_geofence_failed_at_v229", System.currentTimeMillis())
                        .apply()
                }
            }
        } catch (e: Exception) {
            prefs.edit()
                .putString("recovery_geofence_result_v229", "remove_failed:${e.javaClass.simpleName}")
                .putLong("recovery_geofence_failed_at_v229", now)
                .apply()
        }
    }
}
