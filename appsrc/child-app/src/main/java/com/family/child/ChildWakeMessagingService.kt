package com.family.child

import android.content.Context
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * External wake path for explicit Parent refresh requests.
 * High-priority FCM is paired with the existing generic protection notification
 * so the message is user-visible without exposing location/GPS wording.
 */
class ChildWakeMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        WakeTokenSyncWorker.schedule(applicationContext, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        if (message.data[KEY_TYPE] != TYPE_LOCATION_REFRESH) return
        val requestId = com.family.core.WakeRequestPolicy.parse(message.data, System.currentTimeMillis()) ?: run {
            applicationContext.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putString("fcm_wake_start_result","invalid_or_expired").apply(); return
        }

        val app = applicationContext
        val now = System.currentTimeMillis()
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (requestId < prefs.getLong(KEY_PENDING_FCM_REFRESH_ID, 0L) || requestId <= prefs.getLong("refresh_delivered_for_v2210", 0L)) return
        val deliveredHigh = message.priority == RemoteMessage.PRIORITY_HIGH
        val originalHigh = message.originalPriority == RemoteMessage.PRIORITY_HIGH

        val fresh = try { PendingStore(app).useStore { it.acceptWake(requestId) } } catch (_: Exception) {
            prefs.edit().putString("fcm_wake_start_result","inbox_persist_failed").apply(); return
        }
        if (!fresh) {
            if (deliveredHigh || RecoveryStarter.isBatteryOptimizationIgnored(app) || LocationService.running) {
                if (!RecoveryStarter.startLocationService(app,"fcm_duplicate_pending",requestId)) ServiceWatchdogWorker.requestImmediateRecovery(app,"fcm_duplicate_pending")
            } else ServiceWatchdogWorker.requestImmediateRecovery(app,"fcm_duplicate_pending")
            return
        }
        prefs.edit()
            .putLong("fcm_wake_received_at", now)
            .putLong("fcm_wake_request_id", requestId)
            .putLong(KEY_PENDING_FCM_REFRESH_ID, maxOf(requestId, prefs.getLong(KEY_PENDING_FCM_REFRESH_ID, 0L)))
            .putInt("fcm_wake_priority", message.priority)
            .putInt("fcm_wake_original_priority_v229", message.originalPriority)
            .putBoolean("fcm_wake_delivered_high_v229", deliveredHigh)
            .putBoolean("fcm_wake_original_high_v229", originalHigh)
            .putLong("fcm_wake_sent_time", message.sentTime)
            .commit()

        // FCM high priority should result in something user-visible. Reusing the
        // generic protection notification keeps Child UI silent and avoids GPS text.
        val notificationVisible = ProtectionNotifier.ensureVisible(app, "fcm_refresh")
        prefs.edit().putBoolean("fcm_wake_notification_visible_v229", notificationVisible).apply()

        WakeTokenSyncWorker.schedule(app)
        ChildDeviceWriter.write(mapOf("refreshReceivedFor" to requestId, "refreshReceivedAt" to now))
        UnusedAppRestrictionProbe.refresh(app)

        // A delivered HIGH FCM is an Android background-FGS exemption. A user
        // battery-optimization exemption is another valid recovery condition.
        val batteryExempt = RecoveryStarter.isBatteryOptimizationIgnored(app)
        val serviceFresh = LocationService.running && now - prefs.getLong(ServiceWatchdogWorker.KEY_LAST_LOCAL_HEARTBEAT_AT, 0L) in 0L..150_000L
        val mayStart = deliveredHigh || batteryExempt || serviceFresh
        if (!mayStart) {
            prefs.edit()
                .putString("fcm_wake_start_result", "deprioritized_waiting_recovery")
                .putLong("fcm_wake_deprioritized_at_v229", now)
                .apply()
            ServiceWatchdogWorker.requestImmediateRecovery(app, "fcm_deprioritized")
            return
        }

        val started = RecoveryStarter.startLocationService(
            app,
            if (deliveredHigh) "fcm_high" else "fcm_battery_exempt",
            requestId
        )
        prefs.edit()
            .putString("fcm_wake_start_result", if (started) "requested" else "failed")
            .apply()
        if (!started) {
            ServiceWatchdogWorker.requestImmediateRecovery(app, "fcm_wake_start_failed")
        }
    }

    override fun onDeletedMessages() {
        super.onDeletedMessages()
        val app = applicationContext
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("fcm_deleted_messages_at_v229", System.currentTimeMillis())
            .apply()
        WakeTokenSyncWorker.schedule(app)
        ServiceWatchdogWorker.requestImmediateRecovery(app, "fcm_deleted_messages")
    }

    companion object {
        private const val PREFS = "tracking_diag"
        private const val KEY_TYPE = "type"
        private const val KEY_REQUEST_ID = "requestId"
        private const val TYPE_LOCATION_REFRESH = "location_refresh"
        private const val KEY_PENDING_FCM_REFRESH_ID = "pending_fcm_refresh_request_id"

        fun refreshAndSyncToken(context: Context) {
            WakeTokenSyncWorker.schedule(context.applicationContext)
        }

        fun syncToken(context: Context, token: String) {
            WakeTokenSyncWorker.schedule(context.applicationContext, token)
        }
    }
}
