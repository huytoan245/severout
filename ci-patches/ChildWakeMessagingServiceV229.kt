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
        val requestId = message.data[KEY_REQUEST_ID]?.toLongOrNull() ?: 0L
        if (requestId <= 0L) return

        val app = applicationContext
        val now = System.currentTimeMillis()
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val deliveredHigh = message.priority == RemoteMessage.PRIORITY_HIGH
        val originalHigh = message.originalPriority == RemoteMessage.PRIORITY_HIGH

        prefs.edit()
            .putLong("fcm_wake_received_at", now)
            .putLong("fcm_wake_request_id", requestId)
            .putLong(KEY_PENDING_FCM_REFRESH_ID, requestId)
            .putInt("fcm_wake_priority", message.priority)
            .putInt("fcm_wake_original_priority_v229", message.originalPriority)
            .putBoolean("fcm_wake_delivered_high_v229", deliveredHigh)
            .putBoolean("fcm_wake_original_high_v229", originalHigh)
            .putLong("fcm_wake_sent_time", message.sentTime)
            .apply()

        // FCM high priority should result in something user-visible. Reusing the
        // generic protection notification keeps Child UI silent and avoids GPS text.
        val notificationVisible = ProtectionNotifier.ensureVisible(app, "fcm_refresh")
        prefs.edit().putBoolean("fcm_wake_notification_visible_v229", notificationVisible).apply()

        WakeTokenSyncWorker.schedule(app)
        UnusedAppRestrictionProbe.refresh(app)

        // A delivered HIGH FCM is an Android background-FGS exemption. A user
        // battery-optimization exemption is another valid recovery condition.
        val batteryExempt = RecoveryStarter.isBatteryOptimizationIgnored(app)
        val mayStart = deliveredHigh || batteryExempt
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
