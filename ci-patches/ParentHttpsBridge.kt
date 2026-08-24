package com.family.parent

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.google.firebase.auth.FirebaseAuth
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Secondary transport for cases where the Firestore realtime channel is blocked by a Wi-Fi,
 * proxy or DNS path while normal HTTPS is still available. It uses the same Firebase Auth
 * identity and therefore still obeys Firestore Security Rules.
 */
object ParentHttpsBridge {
    private const val DOC_URL = "https://firestore.googleapis.com/v1/projects/family-location-884e5/databases/(default)/documents/devices/child-01"
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    var lastLatencyMs: Long = -1L
        private set

    data class DeviceState(
        val lastLat: Double? = null,
        val lastLon: Double? = null,
        val accuracy: Double? = null,
        val lastSeen: Long = 0L,
        val heartbeatAt: Long = 0L,
        val diagnosticAt: Long = 0L,
        val locationEnabled: Boolean? = null,
        val fineLocationGranted: Boolean? = null,
        val backgroundLocationGranted: Boolean? = null,
        val notificationGranted: Boolean? = null,
        val serviceState: String? = null,
        val status: String? = null,
        val lastError: String? = null,
        val networkType: String? = null,
        val networkValidated: Boolean? = null,
        val networkAt: Long = 0L,
        val firebaseRealtimeOkAt: Long = 0L,
        val firebaseWriteOkAt: Long = 0L,
        val firebaseLatencyMs: Long = 0L,
        val httpsFallbackOkAt: Long = 0L,
        val httpsLatencyMs: Long = 0L,
        val serviceStartedAt: Long = 0L,
        val appVersion: String? = null,
        val refreshRequestedAt: Long = 0L,
        val refreshAckFor: Long = 0L,
        val refreshCompletedFor: Long = 0L,
        val refreshFailedFor: Long = 0L,
        val refreshResult: String? = null,
        val locationReminderRequestedAt: Long = 0L,
        val locationReminderExpiresAt: Long = 0L,
        val locationReminderAckFor: Long = 0L,
        val locationReminderAckAt: Long = 0L,
        val locationReminderResult: String? = null
    )

    fun patch(fields: Map<String, Any?>, callback: (Boolean, String?) -> Unit) {
        withToken(
            onToken = { token ->
                executor.execute {
                    try {
                        val mask = fields.keys.joinToString("&") { "updateMask.fieldPaths=$it" }
                        val start = SystemClock.elapsedRealtime()
                        val connection = (URL("$DOC_URL?$mask").openConnection() as HttpURLConnection).apply {
                            requestMethod = "PATCH"
                            connectTimeout = 8_000
                            readTimeout = 8_000
                            doOutput = true
                            setRequestProperty("Authorization", "Bearer $token")
                            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                        }
                        val jsonFields = JSONObject()
                        fields.forEach { (key, value) -> jsonFields.put(key, encodeValue(value)) }
                        val body = JSONObject().put("fields", jsonFields).toString()
                        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                        val code = connection.responseCode
                        lastLatencyMs = (SystemClock.elapsedRealtime() - start).coerceAtLeast(0L)
                        val message = if (code in 200..299) null else readError(connection)
                        connection.disconnect()
                        post { callback(code in 200..299, message ?: if (code in 200..299) null else "HTTP $code") }
                    } catch (e: Exception) {
                        post { callback(false, e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")) }
                    }
                }
            },
            onError = { post { callback(false, it) } }
        )
    }

    fun readDevice(callback: (DeviceState?, String?) -> Unit) {
        withToken(
            onToken = { token ->
                executor.execute {
                    try {
                        val start = SystemClock.elapsedRealtime()
                        val connection = (URL(DOC_URL).openConnection() as HttpURLConnection).apply {
                            requestMethod = "GET"
                            connectTimeout = 8_000
                            readTimeout = 8_000
                            setRequestProperty("Authorization", "Bearer $token")
                            setRequestProperty("Accept", "application/json")
                        }
                        val code = connection.responseCode
                        lastLatencyMs = (SystemClock.elapsedRealtime() - start).coerceAtLeast(0L)
                        if (code in 200..299) {
                            val body = connection.inputStream.bufferedReader().use { it.readText() }
                            val fields = JSONObject(body).optJSONObject("fields") ?: JSONObject()
                            val state = DeviceState(
                                lastLat = number(fields, "lastLat"),
                                lastLon = number(fields, "lastLon"),
                                accuracy = number(fields, "accuracy"),
                                lastSeen = long(fields, "lastSeen"),
                                heartbeatAt = long(fields, "heartbeatAt"),
                                diagnosticAt = long(fields, "diagnosticAt"),
                                locationEnabled = bool(fields, "locationEnabled"),
                                fineLocationGranted = bool(fields, "fineLocationGranted"),
                                backgroundLocationGranted = bool(fields, "backgroundLocationGranted"),
                                notificationGranted = bool(fields, "notificationGranted"),
                                serviceState = string(fields, "serviceState"),
                                status = string(fields, "status"),
                                lastError = string(fields, "lastError")?.takeIf { it.isNotBlank() },
                                networkType = string(fields, "networkType"),
                                networkValidated = bool(fields, "networkValidated"),
                                networkAt = long(fields, "networkAt"),
                                firebaseRealtimeOkAt = long(fields, "firebaseRealtimeOkAt"),
                                firebaseWriteOkAt = long(fields, "firebaseWriteOkAt"),
                                firebaseLatencyMs = long(fields, "firebaseLatencyMs"),
                                httpsFallbackOkAt = long(fields, "httpsFallbackOkAt"),
                                httpsLatencyMs = long(fields, "httpsLatencyMs"),
                                serviceStartedAt = long(fields, "serviceStartedAt"),
                                appVersion = string(fields, "appVersion"),
                                refreshRequestedAt = long(fields, "refreshRequestedAt"),
                                refreshAckFor = long(fields, "refreshAckFor"),
                                refreshCompletedFor = long(fields, "refreshCompletedFor"),
                                refreshFailedFor = long(fields, "refreshFailedFor"),
                                refreshResult = string(fields, "refreshResult"),
                                locationReminderRequestedAt = long(fields, "locationReminderRequestedAt"),
                                locationReminderExpiresAt = long(fields, "locationReminderExpiresAt"),
                                locationReminderAckFor = long(fields, "locationReminderAckFor"),
                                locationReminderAckAt = long(fields, "locationReminderAckAt"),
                                locationReminderResult = string(fields, "locationReminderResult")
                            )
                            connection.disconnect()
                            post { callback(state, null) }
                        } else {
                            val error = readError(connection)
                            connection.disconnect()
                            post { callback(null, error ?: "HTTP $code") }
                        }
                    } catch (e: Exception) {
                        post { callback(null, e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")) }
                    }
                }
            },
            onError = { post { callback(null, it) } }
        )
    }

    private fun withToken(onToken: (String) -> Unit, onError: (String) -> Unit) {
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            onError("Chưa đăng nhập Firebase")
            return
        }
        user.getIdToken(false)
            .addOnSuccessListener { result ->
                val token = result.token
                if (token.isNullOrBlank()) onError("Không lấy được Firebase ID token") else onToken(token)
            }
            .addOnFailureListener { e -> onError("Token: ${e.javaClass.simpleName}${e.message?.let { ": $it" } ?: ""}") }
    }

    private fun encodeValue(value: Any?): JSONObject = when (value) {
        is Boolean -> JSONObject().put("booleanValue", value)
        is Byte, is Short, is Int, is Long -> JSONObject().put("integerValue", value.toString())
        is Float, is Double -> JSONObject().put("doubleValue", (value as Number).toDouble())
        null -> JSONObject().put("nullValue", "NULL_VALUE")
        else -> JSONObject().put("stringValue", value.toString())
    }

    private fun long(fields: JSONObject, name: String): Long {
        val v = fields.optJSONObject(name) ?: return 0L
        return when {
            v.has("integerValue") -> v.optString("integerValue").toLongOrNull() ?: 0L
            v.has("doubleValue") -> v.optDouble("doubleValue", 0.0).toLong()
            else -> 0L
        }
    }

    private fun number(fields: JSONObject, name: String): Double? {
        val v = fields.optJSONObject(name) ?: return null
        return when {
            v.has("doubleValue") -> v.optDouble("doubleValue")
            v.has("integerValue") -> v.optString("integerValue").toDoubleOrNull()
            else -> null
        }
    }

    private fun bool(fields: JSONObject, name: String): Boolean? {
        val v = fields.optJSONObject(name) ?: return null
        return if (v.has("booleanValue")) v.optBoolean("booleanValue") else null
    }

    private fun string(fields: JSONObject, name: String): String? {
        val v = fields.optJSONObject(name) ?: return null
        return if (v.has("stringValue")) v.optString("stringValue") else null
    }

    private fun readError(connection: HttpURLConnection): String? = try {
        connection.errorStream?.bufferedReader()?.use { it.readText() }?.take(400)
    } catch (_: Exception) { null }

    private fun post(block: () -> Unit) { main.post(block) }
}
