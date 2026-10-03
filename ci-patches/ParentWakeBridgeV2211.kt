package com.family.parent

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.work.*
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.RejectedExecutionException

object ParentWakeBridge {
    data class Result(val status: String, val retry: Boolean, val accepted: Boolean = false)
    private val main = Handler(Looper.getMainLooper())
    private val executor = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(4))
    fun endpoint(): String? = BuildConfig.WAKE_WORKER_URL.takeIf { value ->
        try { val url = URL(value); url.protocol == "https" && url.host.isNotBlank() && url.userInfo == null && url.query == null && url.ref == null && (url.path.isBlank() || url.path == "/") } catch (_: Exception) { false }
    }?.trimEnd('/')
    fun dispatch(request: Long, callback: (Result) -> Unit) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid == null) { callback(Result("no_auth", true)); return }
        try { executor.execute { val result = send(request, uid); main.post { callback(result) } } }
        catch (_: RejectedExecutionException) { callback(Result("transport_busy", true)) }
    }
    fun send(request: Long, uid: String): Result {
        val base = endpoint() ?: return Result("not_configured", false)
        if (System.currentTimeMillis() >= request + 15 * 60_000L) return Result("expired", false)
        try {
            val user = FirebaseAuth.getInstance().currentUser ?: return Result("no_auth", true)
            if (user.uid != uid) return Result("identity_changed", false)
            repeat(2) { index ->
                val token = Tasks.await(user.getIdToken(index > 0), 15, TimeUnit.SECONDS).token ?: return Result("no_id_token", true)
                if (FirebaseAuth.getInstance().currentUser?.uid != uid) return Result("identity_changed", false)
                val c = (URL("$base/v1/wake").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; connectTimeout = 8_000; readTimeout = 8_000; instanceFollowRedirects = false
                    doOutput = true; useCaches = false; setRequestProperty("Connection", "close")
                    setRequestProperty("Content-Type", "application/json"); setRequestProperty("Authorization", "Bearer $token")
                }
                try {
                    val body = JSONObject().put("deviceId", "child-01").put("requestId", request.toString()).put("requestedAt", request)
                    c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                    val code = c.responseCode
                    if (code == 401 && index == 0) return@repeat
                    val stream = if (code in 200..299) c.inputStream else c.errorStream
                    val data = stream?.bufferedReader()?.use { it.readText().take(1024) }?.let { JSONObject(it) } ?: JSONObject()
                    if (code in 200..299 && data.optString("requestId") == request.toString()) return Result(data.optString("status", "accepted"), false, true)
                    return Result(data.optString("error", "http_$code"), code >= 500 || code == 429)
                } finally { c.disconnect() }
            }
            return Result("unauthorized", false)
        } catch (_: Exception) { return Result("network_error", true) }
    }
    private val prepared = java.util.concurrent.atomic.AtomicLong(0L)
    fun prepareCommand(context: Context, request: Long, uid: String): Task<Boolean> {
        val task = TaskCompletionSource<Boolean>()
        val operation = WorkManager.getInstance(context.applicationContext).enqueueUniqueWork("family-parent-wake-outbox", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ParentWakeWorker>().setInputData(workDataOf("request" to request, "uid" to uid))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
        operation.result.addListener({
            try {
                operation.result.get(); prepared.set(request)
                durableCommand(request, uid).addOnSuccessListener { task.trySetResult(it) }.addOnFailureListener { task.trySetException(it) }
            } catch (e: Exception) { task.trySetException(e) }
        }, java.util.concurrent.Executor { main.post(it) })
        return task.task
    }
    fun durableCommand(request: Long, uid: String) = FirebaseFirestore.getInstance().runTransaction { tx ->
        val doc = FirebaseFirestore.getInstance().collection("devices").document("child-01")
        val state = tx.get(doc)
        if (FirebaseAuth.getInstance().currentUser?.uid != uid || (state.getLong("refreshRequestedAt") ?: 0L) > request) false
        else if (state.getLong("refreshRequestedAt") == request && state.getString("refreshRequestedBy") == uid && state.getLong("refreshExpiresAt") == request + 15 * 60_000L) true
        else { tx.set(doc, mapOf("refreshRequestedAt" to request, "refreshExpiresAt" to request + 15 * 60_000L, "refreshRequestedBy" to uid), SetOptions.merge()); true }
    }
    fun persistFallback(request: Long, uid: String, callback: (Boolean, String?) -> Unit) {
        if (prepared.get() != request) { callback(false, "outbox_not_ready"); return }
        try { executor.execute {
            val ok = try { ParentCommandRest.write(request, uid) } catch (_: Exception) { false }
            main.post { callback(ok, if (ok) null else "command_unconfirmed") }
        } } catch (_: RejectedExecutionException) { callback(false, "transport_busy") }
    }
}

class ParentWakeWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val id = inputData.getLong("request", 0L); val uid = inputData.getString("uid") ?: return Result.failure()
        if (id <= 0L || System.currentTimeMillis() >= id + 15 * 60_000L || runAttemptCount >= 8) return Result.failure()
        if (FirebaseAuth.getInstance().currentUser?.uid != uid) return Result.failure()
        try {
            val written = try { Tasks.await(ParentWakeBridge.durableCommand(id, uid), 20, TimeUnit.SECONDS) } catch (_: Exception) { ParentCommandRest.write(id, uid) }
            if (!written || isStopped) return if (isStopped) Result.success() else Result.retry()
            val sent = ParentWakeBridge.send(id, uid)
            applicationContext.getSharedPreferences("wake_diag", Context.MODE_PRIVATE).edit().putLong("request", id).putString("result", sent.status).apply()
            return if (sent.accepted) Result.success() else if (sent.retry) Result.retry() else Result.failure()
        } catch (_: Exception) { return Result.retry() }
    }
}

/** The same newer-command guard as Firestore transactions, over HTTPS fallback. */
internal object ParentCommandRest {
    private const val DOC = "https://firestore.googleapis.com/v1/projects/family-location-884e5/databases/(default)/documents/devices/child-01"
    fun write(id: Long, uid: String): Boolean {
        val user = FirebaseAuth.getInstance().currentUser ?: return false
        if (user.uid != uid || System.currentTimeMillis() >= id + 15 * 60_000L) return false
        val token = Tasks.await(user.getIdToken(false), 15, TimeUnit.SECONDS).token ?: return false
        fun call(url: String, body: JSONObject? = null): JSONObject? {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = if (body == null) "GET" else "PATCH"; connectTimeout = 8_000; readTimeout = 8_000
                instanceFollowRedirects = false; useCaches = false; setRequestProperty("Connection", "close")
                setRequestProperty("Authorization", "Bearer $token"); setRequestProperty("Content-Type", "application/json")
            }
            try {
                if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) } }
                if (c.responseCode !in 200..299) return null
                return JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            } finally { c.disconnect() }
        }
        val before = call(DOC) ?: return false
        val current = before.optJSONObject("fields") ?: JSONObject()
        val existing = current.optJSONObject("refreshRequestedAt")?.optString("integerValue")?.toLongOrNull() ?: 0L
        if (existing > id) return true // superseded: do not mutate the newer command
        if (existing == id && current.optJSONObject("refreshRequestedBy")?.optString("stringValue") == uid) return true
        val time = before.optString("updateTime"); if (time.isBlank() || FirebaseAuth.getInstance().currentUser?.uid != uid) return false
        val fields = JSONObject().put("refreshRequestedAt", JSONObject().put("integerValue", id.toString()))
            .put("refreshExpiresAt", JSONObject().put("integerValue", (id + 15 * 60_000L).toString()))
            .put("refreshRequestedBy", JSONObject().put("stringValue", uid))
        val query = "updateMask.fieldPaths=refreshRequestedAt&updateMask.fieldPaths=refreshExpiresAt&updateMask.fieldPaths=refreshRequestedBy&currentDocument.updateTime=${java.net.URLEncoder.encode(time, "UTF-8")}"
        return call("$DOC?$query", JSONObject().put("fields", fields)) != null
    }
}
