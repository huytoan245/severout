package com.family.child

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Silent external wake path used only for an explicit Parent refresh request.
 * It never displays a notification. Android may still refuse a foreground
 * service start if the app is Force Stopped or hard Restricted; that refusal
 * is recorded and the watchdog is asked to recover when the OS permits it.
 */
class ChildWakeMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        syncToken(applicationContext, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        if (message.data[KEY_TYPE] != TYPE_LOCATION_REFRESH) return

        val requestId = message.data[KEY_REQUEST_ID]?.toLongOrNull() ?: 0L
        if (requestId <= 0L) return

        val now = System.currentTimeMillis()
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putLong("fcm_wake_received_at", now)
            .putLong("fcm_wake_request_id", requestId)
            .putInt("fcm_wake_priority", message.priority)
            .putLong("fcm_wake_sent_time", message.sentTime)
            .apply()

        val intent = Intent(this, LocationService::class.java)
            .putExtra(LocationService.EXTRA_FCM_REFRESH_REQUEST_ID, requestId)
            .putExtra("immediate", true)

        try {
            ContextCompat.startForegroundService(this, intent)
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("fcm_wake_start_result", "requested")
                .apply()
        } catch (e: Exception) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("fcm_wake_start_result", "failed:${e.javaClass.simpleName}")
                .putLong("fcm_wake_start_failed_at", now)
                .apply()
            ServiceWatchdogWorker.requestImmediateRecovery(
                this,
                "fcm_wake_failed_${e.javaClass.simpleName}"
            )
        }
    }

    companion object {
        private const val PREFS = "tracking_diag"
        private const val CHILD_DOC = "child-01"
        private const val KEY_TYPE = "type"
        private const val KEY_REQUEST_ID = "requestId"
        private const val TYPE_LOCATION_REFRESH = "location_refresh"

        fun refreshAndSyncToken(context: Context) {
            FirebaseMessaging.getInstance().token
                .addOnSuccessListener { syncToken(context, it) }
                .addOnFailureListener { e ->
                    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .putString("fcm_token_result", "failed:${e.javaClass.simpleName}")
                        .putLong("fcm_token_failed_at", System.currentTimeMillis())
                        .apply()
                }
        }

        fun syncToken(context: Context, token: String) {
            if (token.isBlank()) return
            val now = System.currentTimeMillis()
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("fcm_token", token)
                .putLong("fcm_token_local_at", now)
                .apply()

            val auth = FirebaseAuth.getInstance()
            fun publish() {
                val payload = mapOf<String, Any?>(
                    "fcmToken" to token,
                    "fcmTokenUpdatedAt" to now,
                    "fcmWakeClientVersion" to BuildConfig.VERSION_NAME
                )
                FirebaseFirestore.getInstance()
                    .collection("devices")
                    .document(CHILD_DOC)
                    .set(payload, SetOptions.merge())
                    .addOnSuccessListener {
                        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                            .putString("fcm_token_result", "firestore_ok")
                            .putLong("fcm_token_cloud_at", System.currentTimeMillis())
                            .apply()
                    }
                    .addOnFailureListener { e ->
                        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                            .putString("fcm_token_result", "firestore_failed:${e.javaClass.simpleName}")
                            .apply()
                    }
                ChildHttpsBridge.patch(payload) { ok, error ->
                    if (ok) {
                        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                            .putString("fcm_token_https_result", "ok")
                            .putLong("fcm_token_https_at", System.currentTimeMillis())
                            .apply()
                    } else if (error != null) {
                        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                            .putString("fcm_token_https_result", "failed")
                            .apply()
                    }
                }
            }

            if (auth.currentUser != null) {
                publish()
            } else {
                auth.signInAnonymously()
                    .addOnSuccessListener { publish() }
                    .addOnFailureListener { e ->
                        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                            .putString("fcm_token_result", "auth_failed:${e.javaClass.simpleName}")
                            .apply()
                    }
            }
        }
    }
}
