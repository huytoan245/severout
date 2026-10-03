package com.family.child

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.family.core.DeviceMutationPolicy
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import java.net.URLEncoder
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.RejectedExecutionException

/** Bounded HTTPS transport; every connection closes and stale mutations use CAS. */
object ChildHttpsBridge {
    private const val DOC_URL = "https://firestore.googleapis.com/v1/projects/family-location-884e5/databases/(default)/documents/devices/child-01"
    private val executor = ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(16))
    private val main = Handler(Looper.getMainLooper())
    @Volatile var lastLatencyMs: Long = -1L; private set
    data class DeviceState(val refreshRequestedAt: Long = 0L, val refreshAckFor: Long = 0L,
        val refreshCompletedFor: Long = 0L, val refreshFailedFor: Long = 0L, val heartbeatAt: Long = 0L,
        val locationReminderRequestedAt: Long = 0L, val locationReminderExpiresAt: Long = 0L)
    private fun task(callback: (Boolean, String?) -> Unit, action: (String, String) -> Boolean) {
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) { callback(false, "no_auth"); return }
        user.getIdToken(false).addOnSuccessListener { result ->
            val token = result.token
            if (token.isNullOrBlank()) { callback(false, "no_id_token"); return@addOnSuccessListener }
            try { executor.execute {
                val start = SystemClock.elapsedRealtime()
                val ok = try { FirebaseAuth.getInstance().currentUser?.uid == user.uid && action(token, user.uid) } catch (_: Exception) { false }
                lastLatencyMs = SystemClock.elapsedRealtime() - start
                main.post { callback(ok, if (ok) null else "https_unconfirmed") }
            } } catch (_: RejectedExecutionException) { callback(false, "transport_busy") }
        }.addOnFailureListener { callback(false, "token_unavailable") }
    }
    private fun query(fields: Map<String, Any?>) = fields.keys.joinToString("&") { "updateMask.fieldPaths=" + URLEncoder.encode(it, "UTF-8") }
    fun patch(fields: Map<String, Any?>, callback: ((Boolean, String?) -> Unit)? = null) {
        task({ ok, error -> callback?.invoke(ok, error) }) { token, uid ->
            val values = fields.filterValues { it !is FieldValue } + ("httpsFallbackOkAt" to System.currentTimeMillis())
            FirebaseAuth.getInstance().currentUser?.uid == uid && BoundedRest.call("$DOC_URL?${query(values)}", token, BoundedRest.encode(values)) != null
        }
    }
    fun patchGuarded(fields: Map<String, Any?>, owner: String, callback: (Boolean) -> Unit) {
        task({ ok, _ -> callback(ok) }) { token, uid ->
            if (uid != owner) return@task false
            val before = BoundedRest.call(DOC_URL, token) ?: return@task false
            val selected = DeviceMutationPolicy.select(BoundedRest.decode(before), fields.filterValues { it !is FieldValue })
            val finishing = fields.containsKey("refreshCompletedFor") || fields.containsKey("refreshFailedFor")
            if (selected.isEmpty() || (finishing && !selected.containsKey("refreshCompletedFor") && !selected.containsKey("refreshFailedFor"))) return@task false
            val time = before.optString("updateTime")
            if (time.isBlank() || FirebaseAuth.getInstance().currentUser?.uid != uid) return@task false
            BoundedRest.call("$DOC_URL?${query(selected)}&currentDocument.updateTime=${URLEncoder.encode(time,"UTF-8")}", token, BoundedRest.encode(selected)) != null
        }
    }
    fun patchEvent(documentId: String, fields: Map<String, Any?>, callback: (Boolean, String?) -> Unit) {
        task(callback) { token, uid -> FirebaseAuth.getInstance().currentUser?.uid == uid &&
            BoundedRest.call("$DOC_URL/events/${URLEncoder.encode(documentId,"UTF-8")}?${query(fields)}", token, BoundedRest.encode(fields)) != null }
    }
    fun readDevice(callback: (DeviceState?, String?) -> Unit) {
        var state: DeviceState? = null
        task({ ok, error -> callback(if (ok) state else null, error) }) { token, _ ->
            val document = BoundedRest.call(DOC_URL, token) ?: return@task false
            val m = BoundedRest.decode(document)
            fun n(key: String) = (m[key] as? Number)?.toLong() ?: 0L
            state = DeviceState(n("refreshRequestedAt"), n("refreshAckFor"), n("refreshCompletedFor"), n("refreshFailedFor"), n("heartbeatAt"), n("locationReminderRequestedAt"), n("locationReminderExpiresAt")); true
        }
    }
}
