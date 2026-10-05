package com.family.child

import android.content.Context
import androidx.work.*
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import com.google.firebase.messaging.FirebaseMessaging
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class WakeTokenSyncWorker(appContext: Context, params: WorkerParameters) : Worker(appContext, params) {
    override fun doWork(): Result {
        val app = applicationContext
        val prefs = app.getSharedPreferences("tracking_diag", Context.MODE_PRIVATE)
        fun status(value: String) { prefs.edit().putString("fcm_token_worker_result_v229", value).apply() }
        prefs.edit().putLong("fcm_token_worker_run_at_v229", System.currentTimeMillis()).apply()
        try {
            val before = TokenState.current(app)
            val sdkToken = Tasks.await(FirebaseMessaging.getInstance().token, 20, TimeUnit.SECONDS)
            val current = TokenState.observe(app, sdkToken, before.generation)
            if (current.token.isBlank() || isStopped) return Result.retry()
            val auth = FirebaseAuth.getInstance()
            if (auth.currentUser == null) Tasks.await(auth.signInAnonymously(), 20, TimeUnit.SECONDS)
            val uid = auth.currentUser?.uid ?: return Result.retry()
            IdentityDiagnostics.record(uid)
            fun stillCurrent() = !isStopped && auth.currentUser?.uid == uid && TokenState.current(app) == current
            if (!stillCurrent()) return Result.retry()
            val fields = mapOf<String, Any>("fcmToken" to current.token, "fcmTokenGeneration" to current.generation,
                "fcmTokenUpdatedAt" to current.generation, "fcmTokenOwnerUid" to uid,
                "fcmWakeClientVersion" to BuildConfig.VERSION_NAME, "fcmTokenSyncProtocol" to "v230") + SetupDiagnostics.fields(app)
            val db = FirebaseFirestore.getInstance()
            val doc = db.collection("devices").document("child-01")
            try {
                val written = Tasks.await(db.runTransaction { tx ->
                    val remote = tx.get(doc)
                    if (!stillCurrent()) false
                    else if ((remote.getLong("fcmTokenGeneration") ?: 0L) > current.generation ||
                        ((remote.getLong("fcmTokenGeneration") ?: 0L) == current.generation && (remote.getString("fcmToken") != current.token || remote.getString("fcmTokenOwnerUid") != uid))) {
                        TokenState.rebase(app, current, remote.getLong("fcmTokenGeneration") ?: 0L); false
                    }
                    else { tx.set(doc, fields, SetOptions.merge()); true }
                }, 20, TimeUnit.SECONDS)
                if (written && stillCurrent()) {
                    val remote = Tasks.await(doc.get(Source.SERVER), 20, TimeUnit.SECONDS)
                    if (remote.getString("fcmToken") == current.token && remote.getLong("fcmTokenGeneration") == current.generation && remote.getString("fcmTokenOwnerUid") == uid && stillCurrent() && TokenState.confirm(app, current)) {
                        status("firestore_verified"); return Result.success()
                    }
                }
            } catch (e: Exception) { status("firestore:${e.javaClass.simpleName}") }
            if (!stillCurrent()) return Result.retry()
            val idToken = Tasks.await(auth.currentUser!!.getIdToken(false), 15, TimeUnit.SECONDS).token ?: return Result.retry()
            if (TokenCasTransport.upload(app, current, uid, fields, idToken, ::stillCurrent) && stillCurrent() && TokenState.confirm(app, current)) {
                status("https_readback_verified"); return Result.success()
            }
            status("server_unconfirmed"); return Result.retry()
        } catch (e: Exception) { status("retry:${e.javaClass.simpleName}"); return Result.retry() }
    }
    companion object {
        private const val UNIQUE_NOW = "family-location-fcm-token-sync-v229"
        private const val UNIQUE_PERIODIC = "family-location-fcm-token-periodic-v229"
        private fun constraints() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        fun schedule(context: Context, token: String? = null) {
            token?.let { TokenState.observe(context, it) }
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(UNIQUE_NOW,
                if (token == null) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<WakeTokenSyncWorker>().setConstraints(constraints())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
            ensurePeriodic(context)
        }
        fun ensurePeriodic(context: Context) {
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(UNIQUE_PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<WakeTokenSyncWorker>(6, TimeUnit.HOURS).setConstraints(constraints())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
        }
    }
}

/** Conditional updateTime prevents a late old-token REST request overwriting rotation. */
internal object TokenCasTransport {
    private const val DOC = "https://firestore.googleapis.com/v1/projects/family-location-884e5/databases/(default)/documents/devices/child-01"
    private fun request(url: String, token: String, body: JSONObject? = null): JSONObject? = BoundedRest.call(url,token,body)
    fun upload(context: Context, snapshot: TokenSnapshot, uid: String, fields: Map<String, Any>, token: String, current: () -> Boolean): Boolean {
        val before = request(DOC, token) ?: return false
        val remote = before.optJSONObject("fields") ?: JSONObject()
        if (!current()) return false
        val generation = remote.optJSONObject("fcmTokenGeneration")?.optString("integerValue")?.toLongOrNull() ?: 0L
        if (generation > snapshot.generation || (generation == snapshot.generation && (remote.optJSONObject("fcmToken")?.optString("stringValue") != snapshot.token || remote.optJSONObject("fcmTokenOwnerUid")?.optString("stringValue") != uid))) {
            TokenState.rebase(context, snapshot, generation); return false
        }
        val updateTime = before.optString("updateTime"); if (updateTime.isBlank()) return false
        val mask = fields.keys.joinToString("&") { "updateMask.fieldPaths=$it" }
        val encoded = BoundedRest.encode(fields)
        if (request("$DOC?$mask&currentDocument.updateTime=${URLEncoder.encode(updateTime, "UTF-8")}", token, encoded) == null || !current()) return false
        val confirmed = request(DOC, token)?.optJSONObject("fields") ?: return false
        return current() && confirmed.optJSONObject("fcmToken")?.optString("stringValue") == snapshot.token &&
            confirmed.optJSONObject("fcmTokenGeneration")?.optString("integerValue")?.toLongOrNull() == snapshot.generation &&
            confirmed.optJSONObject("fcmTokenOwnerUid")?.optString("stringValue") == uid
    }
}
