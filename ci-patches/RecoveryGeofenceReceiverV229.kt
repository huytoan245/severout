package com.family.child

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent

/** Handles Google Play services geofence EXIT events as a recovery trigger. */
class RecoveryGeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val event = GeofencingEvent.fromIntent(intent)
        if (event == null) {
            prefs.edit()
                .putString("recovery_geofence_event_result_v229", "null_event")
                .putLong("recovery_geofence_event_at_v229", now)
                .apply()
            return
        }
        if (event.hasError()) {
            prefs.edit()
                .putString("recovery_geofence_event_result_v229", "error:${event.errorCode}")
                .putLong("recovery_geofence_event_at_v229", now)
                .apply()
            return
        }
        if (event.geofenceTransition != Geofence.GEOFENCE_TRANSITION_EXIT) return

        val lastHeartbeat = prefs.getLong(ServiceWatchdogWorker.KEY_LAST_LOCAL_HEARTBEAT_AT, 0L)
        val serviceFresh = lastHeartbeat > 0L && now - lastHeartbeat <= SERVICE_FRESH_MS
        prefs.edit()
            .putString("recovery_geofence_event_result_v229", if (serviceFresh) "exit_service_alive" else "exit_recovery")
            .putLong("recovery_geofence_event_at_v229", now)
            .putBoolean("recovery_geofence_service_fresh_v229", serviceFresh)
            .apply()

        // Re-arm immediately around the latest observed point so the independent
        // recovery trigger remains useful after this one-shot EXIT transition.
        RecoveryGeofenceManager.rearmLatestObserved(app, "exit_event")
        WakeTokenSyncWorker.schedule(app)
        UnusedAppRestrictionProbe.refresh(app)

        if (serviceFresh) return
        ProtectionNotifier.ensureVisible(app, "geofence_exit")
        val started = RecoveryStarter.startLocationService(app, "geofence_exit")
        if (!started) {
            ServiceWatchdogWorker.requestImmediateRecovery(app, "geofence_exit_start_failed")
        }
    }

    companion object {
        private const val PREFS = "tracking_diag"
        private const val SERVICE_FRESH_MS = 150_000L
    }
}
