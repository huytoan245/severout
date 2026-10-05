package com.family.enrollment

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

class EnrollmentFailure(val code: String, val status: Int, val requiredGeneration: Long = 0L) : Exception(code) {
    val retryable: Boolean get() = status >= 500 || status == 429 || status == 401 || status == 409 || code == "invalid_nonce"
}

/** Blocking bounded transport. Call exclusively from Worker/background threads. */
object EnrollmentClient {
    private const val PREFS = "family_enrollment_v231"
    fun registered(context: Context, role: String): Boolean {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return false
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("registered_$role", null) == uid
    }
    @Synchronized fun ensureRegistered(context: Context, base: String, role: String, bootstrap: String, force: Boolean = false): String {
        require(role == "parent" || role == "child")
        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser == null) Tasks.await(auth.signInAnonymously(), 20, TimeUnit.SECONDS)
        val uid = auth.currentUser?.uid ?: throw EnrollmentFailure("no_auth", 503)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val publicKey = DeviceIdentity.publicKey()
        if (!force && prefs.getString("registered_$role", null) == uid && prefs.getString("registered_key", null) == publicKey) return uid
        val result = try {
            signed(context, base, role, "register", JSONObject().put("familyId", "family-01").put("deviceId", "child-01").put("version", "2.3.1"), bootstrap)
        } catch (e: EnrollmentFailure) {
            if (e.status == 403 && !e.retryable) prefs.edit().remove("registered_$role").commit()
            throw e
        }
        check(result.optBoolean("registered") && result.optString("role") == role && auth.currentUser?.uid == uid)
        check(prefs.edit().putString("registered_$role", uid).putString("registered_key", publicKey).commit())
        return uid
    }
    @Synchronized fun signed(context: Context, base: String, role: String, purpose: String, payload: JSONObject, bootstrap: String = ""): JSONObject {
        val user = FirebaseAuth.getInstance().currentUser ?: throw EnrollmentFailure("no_auth", 503)
        val uid = user.uid
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = payload.toString()
        val journal = EnrollmentJournal(prefs, role)
        var pending = journal.read(uid, purpose, raw, System.currentTimeMillis())
        if (pending == null) {
            val challenge = call(base, "/v1/challenge", JSONObject().put("familyId", "family-01").put("deviceId", "child-01").put("role", role).put("purpose", purpose), uid)
            val nonce = challenge.getString("nonce")
            val signedRaw = BootstrapPayload.forChallenge(purpose, payload, challenge, bootstrap).toString()
            val proof = JSONObject().put("nonce", nonce).put("signature", DeviceIdentity.sign(EnrollmentProof.message(uid, role, nonce, purpose, signedRaw)))
                .put("publicKey", DeviceIdentity.publicKey()).put("payload", signedRaw)
            pending = JSONObject().put("uid", uid).put("purpose", purpose).put("payload", raw).put("savedAt", System.currentTimeMillis()).put("proof", proof)
            journal.save(pending)
        }
        val path = when (purpose) { "register" -> "/v1/register/$role"; "token" -> "/v1/token"; "wake" -> "/v1/wake"; else -> error("invalid purpose") }
        try {
            val result = call(base, path, pending.getJSONObject("proof"), uid)
            journal.clear()
            return result
        } catch (e: EnrollmentFailure) {
            if (e.status in 400..499 && e.status != 401 && e.status != 429 && e.code != "state_changed" && e.code != "registration_raced") journal.clear()
            throw e
        }
    }
    private fun call(base: String, path: String, body: JSONObject, uid: String): JSONObject {
        val origin = URL(base)
        require(origin.protocol == "https" && origin.host.isNotBlank() && origin.userInfo == null && origin.query == null && origin.ref == null && (origin.path.isEmpty() || origin.path == "/"))
        repeat(2) { index ->
            val user = FirebaseAuth.getInstance().currentUser ?: throw EnrollmentFailure("no_auth", 503)
            if (user.uid != uid) throw EnrollmentFailure("identity_changed", 403)
            val token = Tasks.await(user.getIdToken(index > 0), 15, TimeUnit.SECONDS).token ?: throw EnrollmentFailure("no_id_token", 503)
            val connection = (URL(base.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; connectTimeout = 8_000; readTimeout = 8_000; instanceFollowRedirects = false
                doOutput = true; useCaches = false; setRequestProperty("Connection", "close")
                setRequestProperty("Content-Type", "application/json"); setRequestProperty("Authorization", "Bearer $token")
            }
            try {
                if (FirebaseAuth.getInstance().currentUser?.uid != uid) throw EnrollmentFailure("identity_changed", 403)
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val status = connection.responseCode
                if (status == 401 && index == 0) return@repeat
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val bytes = stream?.use {
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(1024)
                    while (output.size() <= 8192) {
                        val count = it.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                } ?: ByteArray(0)
                check(bytes.size <= 8192)
                val response = JSONObject(String(bytes, Charsets.UTF_8))
                if (status !in 200..299) throw EnrollmentFailure(response.optString("error", "http_$status"), status, response.optLong("requiredGeneration"))
                if (FirebaseAuth.getInstance().currentUser?.uid != uid) throw EnrollmentFailure("identity_changed", 403)
                return response
            } finally { connection.disconnect() }
        }
        throw EnrollmentFailure("unauthorized", 401)
    }
}
