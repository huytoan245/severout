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
    private val executor = java.util.concurrent.ThreadPoolExecutor(2, 2, 30, java.util.concurrent.TimeUnit.SECONDS, java.util.concurrent.ArrayBlockingQueue<Runnable>(16))
    private fun submit(rejected: () -> Unit, action: () -> Unit) {
        try { executor.execute(action) } catch (_: java.util.concurrent.RejectedExecutionException) { post(rejected) }
    }
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    var lastLatencyMs: Long = -1L
        private set

    data class RemoteEvent(
        val type: String = "",
        val time: Long = 0L,
        val lat: Double? = null,
        val lon: Double? = null,
        val accuracy: Double? = null,
        val id: String = "",
        val offlineSince: Long = 0L,
        val durationMs: Long = 0L
    )

    data class DeviceState(
        val lastLat: Double? = null,
        val lastLon: Double? = null,
        val accuracy: Double? = null,
        val lastSeen: Long = 0L,
        val heartbeatAt: Long = 0L,
        val diagnosticAt: Long = 0L,
        val locationEnabled: Boolean? = null,
        val locationOffSince: Long = 0L,
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
        val syncState: String? = null,
        val syncPendingCount: Long = 0L,
        val syncCompletedAt: Long = 0L,
        val syncUploadedCount: Long = 0L,
        val routeMode: String? = null,
        val serviceInstanceId: String? = null,
        val serviceStartCount: Long = 0L,
        val serviceRestartCount: Long = 0L,
        val lastLocalServiceHeartbeatAt: Long = 0L,
        val lastGpsCallbackAt: Long = 0L,
        val lastCloudHeartbeatAttemptAt: Long = 0L,
        val batteryOptimizationIgnored: Boolean? = null,
        val backgroundRestricted: Boolean? = null,
        val appStandbyRestricted: Boolean? = null,
        val watchdogLastRunAt: Long = 0L,
        val watchdogResult: String? = null,
        val cellularFailoverActive: Boolean? = null,
        val cellularFailoverSince: Long = 0L,
        val wifiServerProbeOkAt: Long = 0L,
        val wifiServerProbeFailAt: Long = 0L,
        val wifiServerFailureCount: Long = 0L,
        val cellularAvailable: Boolean? = null,
        val networkOfflineSince: Long = 0L,
        val lastOfflineStartAt: Long = 0L,
        val lastOfflineEndAt: Long = 0L,
        val lastOfflineDurationMs: Long = 0L,
        val refreshRequestedAt: Long = 0L,
        val refreshAckFor: Long = 0L,
        val refreshCompletedFor: Long = 0L,
        val refreshFailedFor: Long = 0L,
        val refreshResult: String? = null,
        val refreshReceivedFor: Long = 0L,
        val refreshServiceFor: Long = 0L,
        val refreshLocatingFor: Long = 0L,
        val wakeBackendFor: Long = 0L,
        val survivalDiagnostics: Map<String, String> = emptyMap(),
        val wakeDispatchFor: Long = 0L,
        val wakeDispatchResult: String? = null,
        val locationReminderRequestedAt: Long = 0L,
        val locationReminderExpiresAt: Long = 0L,
        val locationReminderAckFor: Long = 0L,
        val locationReminderAckAt: Long = 0L,
        val locationReminderResult: String? = null
    )

    fun patch(fields: Map<String, Any?>, callback: (Boolean, String?) -> Unit) {
        withToken(
            onToken = { token ->
                submit({ callback(false, "transport_busy") }) {
                    try {
                        val mask = fields.keys.joinToString("&") { "updateMask.fieldPaths=$it" }
                        val start = SystemClock.elapsedRealtime()
                        val connection = (URL("$DOC_URL?$mask").openConnection() as HttpURLConnection).apply {
                            requestMethod = "PATCH"
                            instanceFollowRedirects = false
                            setRequestProperty("Connection", "close")
                            useCaches = false
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
                submit({ callback(null, "transport_busy") }) {
                    try {
                        val start = SystemClock.elapsedRealtime()
                        val connection = (URL(DOC_URL).openConnection() as HttpURLConnection).apply {
                            requestMethod = "GET"
                            instanceFollowRedirects = false
                            setRequestProperty("Connection", "close")
                            useCaches = false
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
                                locationOffSince = long(fields, "locationOffSince"),
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
                                syncState = string(fields, "syncState"),
                                syncPendingCount = long(fields, "syncPendingCount"),
                                syncCompletedAt = long(fields, "syncCompletedAt"),
                                syncUploadedCount = long(fields, "syncUploadedCount"),
                                routeMode = string(fields, "routeMode"),
                                serviceInstanceId = string(fields, "serviceInstanceId"),
                                serviceStartCount = long(fields, "serviceStartCount"),
                                serviceRestartCount = long(fields, "serviceRestartCount"),
                                lastLocalServiceHeartbeatAt = long(fields, "lastLocalServiceHeartbeatAt"),
                                lastGpsCallbackAt = long(fields, "lastGpsCallbackAt"),
                                lastCloudHeartbeatAttemptAt = long(fields, "lastCloudHeartbeatAttemptAt"),
                                batteryOptimizationIgnored = bool(fields, "batteryOptimizationIgnored"),
                                backgroundRestricted = bool(fields, "backgroundRestricted"),
                                appStandbyRestricted = bool(fields, "appStandbyRestricted"),
                                watchdogLastRunAt = long(fields, "watchdogLastRunAt"),
                                watchdogResult = string(fields, "watchdogResult"),
                                cellularFailoverActive = bool(fields, "cellularFailoverActive"),
                                cellularFailoverSince = long(fields, "cellularFailoverSince"),
                                wifiServerProbeOkAt = long(fields, "wifiServerProbeOkAt"),
                                wifiServerProbeFailAt = long(fields, "wifiServerProbeFailAt"),
                                wifiServerFailureCount = long(fields, "wifiServerFailureCount"),
                                cellularAvailable = bool(fields, "cellularAvailable"),
                                networkOfflineSince = long(fields, "networkOfflineSince"),
                                lastOfflineStartAt = long(fields, "lastOfflineStartAt"),
                                lastOfflineEndAt = long(fields, "lastOfflineEndAt"),
                                lastOfflineDurationMs = long(fields, "lastOfflineDurationMs"),
                                refreshRequestedAt = long(fields, "refreshRequestedAt"),
                                refreshAckFor = long(fields, "refreshAckFor"),
                                refreshCompletedFor = long(fields, "refreshCompletedFor"),
                                refreshFailedFor = long(fields, "refreshFailedFor"),
                                refreshResult = string(fields, "refreshResult"),
                                refreshReceivedFor = long(fields, "refreshReceivedFor"),
                                refreshServiceFor = long(fields, "refreshServiceFor"),
                                refreshLocatingFor = long(fields, "refreshLocatingFor"),
                                wakeBackendFor = long(fields, "wakeBackendFor"),
                                survivalDiagnostics = SurvivalHealth.from(SurvivalHealth.fields.associateWith { key ->
                                    val v = fields.optJSONObject(key)
                                    when { v == null || v.has("nullValue") -> null; v.has("booleanValue") -> v.getBoolean("booleanValue"); v.has("integerValue") -> v.getString("integerValue").toLongOrNull(); else -> v.optString("stringValue", "") }
                                }),
                                wakeDispatchFor = long(fields, "wakeDispatchFor"),
                                wakeDispatchResult = string(fields, "wakeDispatchResult"),
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

    fun readEvents(dayStart: Long, dayEnd: Long, callback: (List<RemoteEvent>?, String?) -> Unit) {
        withToken(
            onToken = { token ->
                submit({ callback(null, "transport_busy") }) {
                    try {
                        val url = "https://firestore.googleapis.com/v1/projects/family-location-884e5/databases/(default)/documents/devices/child-01:runQuery"
                        val query = JSONObject().put("structuredQuery", JSONObject()
                            .put("from", org.json.JSONArray().put(JSONObject().put("collectionId", "events")))
                            .put("where", JSONObject().put("compositeFilter", JSONObject()
                                .put("op", "AND")
                                .put("filters", org.json.JSONArray()
                                    .put(JSONObject().put("fieldFilter", JSONObject()
                                        .put("field", JSONObject().put("fieldPath", "time"))
                                        .put("op", "GREATER_THAN_OR_EQUAL")
                                        .put("value", JSONObject().put("integerValue", dayStart.toString()))))
                                    .put(JSONObject().put("fieldFilter", JSONObject()
                                        .put("field", JSONObject().put("fieldPath", "time"))
                                        .put("op", "LESS_THAN")
                                        .put("value", JSONObject().put("integerValue", dayEnd.toString())))))))
                            .put("orderBy", org.json.JSONArray().put(JSONObject()
                                .put("field", JSONObject().put("fieldPath", "time"))
                                .put("direction", "ASCENDING")))
                            .put("limit", 5000))
                        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                            requestMethod = "POST"
                            instanceFollowRedirects = false
                            setRequestProperty("Connection", "close")
                            useCaches = false
                            connectTimeout = 8_000
                            readTimeout = 12_000
                            doOutput = true
                            setRequestProperty("Authorization", "Bearer $token")
                            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                            setRequestProperty("Accept", "application/json")
                        }
                        connection.outputStream.use { it.write(query.toString().toByteArray(Charsets.UTF_8)) }
                        val code = connection.responseCode
                        if (code in 200..299) {
                            val body = connection.inputStream.bufferedReader().use { it.readText() }
                            val rows = org.json.JSONArray(body)
                            val result = mutableListOf<RemoteEvent>()
                            for (i in 0 until rows.length()) {
                                val doc = rows.optJSONObject(i)?.optJSONObject("document") ?: continue
                                val fields = doc.optJSONObject("fields") ?: continue
                                result += RemoteEvent(
                                    type = string(fields, "type") ?: "",
                                    time = long(fields, "time"),
                                    lat = number(fields, "lat"),
                                    lon = number(fields, "lon"),
                                    accuracy = number(fields, "accuracy"),
                                    id = string(fields, "id") ?: "",
                                    offlineSince = long(fields, "offlineSince"),
                                    durationMs = long(fields, "durationMs")
                                )
                            }
                            connection.disconnect()
                            post { callback(result, null) }
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
