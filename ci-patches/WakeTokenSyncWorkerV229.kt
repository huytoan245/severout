package com.family.child

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import com.google.firebase.messaging.FirebaseMessaging
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Persists and retries the current FCM token until the server confirms it.
 * The wake backend cannot work reliably if the Child has a local token that was
 * never published because the network happened to fail during installation.
 */
class WakeTokenSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : Worker(appContext, params) {

    override fun doWork(): Result {
        val app = applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        prefs.edit().putLong("fcm_token_worker_run_at_v229", now).apply()

        val token = try {
            inputData.getString(KEY_TOKEN)?.takeIf { it.isNotBlank() }
                ?: Tasks.await(FirebaseMessaging.getInstance().token, 20, TimeUnit.SECONDS)
        } catch (e: Exception) {
            prefs.edit()
                .putString("fcm_token_worker_result_v229", "token_failed:${e.javaClass.simpleName}")
                .apply()
            return Result.retry()
        }
        if (token.isBlank()) return Result.retry()

        prefs.edit()
            .putString("fcm_token", token)
            .putLong("fcm_token_local_at", now)
            .apply()

        try {
            val auth = FirebaseAuth.getInstance()
            if (auth.currentUser == null) {
                Tasks.await(auth.signInAnonymously(), 20, TimeUnit.SECONDS)
            }
        } catch (e: Exception) {
            prefs.edit()
                .putString("fcm_token_worker_result_v229", "auth_failed:${e.javaClass.simpleName}")
                .apply()
            return Result.retry()
        }

        val payload = mapOf<String, Any?>(
            "fcmToken" to token,
            "fcmTokenUpdatedAt" to now,
            "fcmWakeClientVersion" to BuildConfig.VERSION_NAME,
            "fcmTokenSyncProtocol" to "v229"
        )
        val doc = FirebaseFirestore.getInstance().collection("devices").document(CHILD_DOC)

        try {
            Tasks.await(doc.set(payload, SetOptions.merge()), 20, TimeUnit.SECONDS)
            val snapshot = Tasks.await(doc.get(Source.SERVER), 20, TimeUnit.SECONDS)
            if (snapshot.getString("fcmToken") == token) {
                prefs.edit()
                    .putString("fcm_token_worker_result_v229", "firestore_verified")
                    .putLong("fcm_token_cloud_at", System.currentTimeMillis())
                    .putBoolean("fcm_wake_ready_v229", true)
                    .apply()
                return Result.success()
            }
        } catch (e: Exception) {
            prefs.edit()
                .putString("fcm_token_firestore_error_v229", e.javaClass.simpleName)
                .apply()
        }

        val done = CountDownLatch(1)
        val ok = AtomicBoolean(false)
        try {
            ChildHttpsBridge.patch(payload) { success, _ ->
                ok.set(success)
                done.countDown()
            }
            done.await(20, TimeUnit.SECONDS)
        } catch (_: Exception) {
        }

        return if (ok.get()) {
            prefs.edit()
                .putString("fcm_token_worker_result_v229", "https_confirmed")
                .putLong("fcm_token_cloud_at", System.currentTimeMillis())
                .putBoolean("fcm_wake_ready_v229", true)
                .apply()
            Result.success()
        } else {
            prefs.edit()
                .putString("fcm_token_worker_result_v229", "server_unconfirmed")
                .putBoolean("fcm_wake_ready_v229", false)
                .apply()
            Result.retry()
        }
    }

    companion object {
        private const val PREFS = "tracking_diag"
        private const val CHILD_DOC = "child-01"
        private const val KEY_TOKEN = "token"
        private const val UNIQUE_NOW = "family-location-fcm-token-sync-v229"
        private const val UNIQUE_PERIODIC = "family-location-fcm-token-periodic-v229"

        private fun constraints() = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        fun schedule(context: Context, token: String? = null) {
            val request = OneTimeWorkRequestBuilder<WakeTokenSyncWorker>()
                .setConstraints(constraints())
                .setInputData(workDataOf(KEY_TOKEN to (token ?: "")))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                UNIQUE_NOW,
                ExistingWorkPolicy.REPLACE,
                request
            )
            ensurePeriodic(context)
        }

        fun ensurePeriodic(context: Context) {
            val periodic = PeriodicWorkRequestBuilder<WakeTokenSyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(constraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                periodic
            )
        }
    }
}
