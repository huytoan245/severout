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
        val durationMs: Long = 0L,
        val documentId: String = ""
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
        val locationReminderResult: String? = null,
        val refreshEvidence: Map<String,Any?> = emptyMap()
    )

    fun patch(fields: Map<String, Any?>, callback: (Boolean, String?) -> Unit) {
        withToken(
            onToken = { token ->
                submit({ callback(false, "transport_busy") }) {
                    try {
                        val scopedFields = if (fields.containsKey("locationReminderRequestedAt")) {
                            val c = (URL(DOC_URL.substringBefore("/devices/") + "/families/family-01").openConnection() as HttpURLConnection).apply {
                                connectTimeout = 8_000; readTimeout = 8_000; instanceFollowRedirects = false
                                setRequestProperty("Authorization", "Bearer $token")
                            }
                            val family = try { check(c.responseCode in 200..299); JSONObject(c.inputStream.bufferedReader().use { it.readText() }).getJSONObject("fields") } finally { c.disconnect() }
                            check(family.getJSONObject("parentUid").getString("stringValue") == FirebaseAuth.getInstance().currentUser?.uid)
                            val before = (URL(DOC_URL).openConnection() as HttpURLConnection).apply {
                                connectTimeout = 8_000; readTimeout = 8_000; instanceFollowRedirects = false
                                setRequestProperty("Authorization", "Bearer $token")
                            }
                            val previous = try { check(before.responseCode in 200..299); JSONObject(before.inputStream.bufferedReader().use { it.readText() }).getJSONObject("fields") } finally { before.disconnect() }
                            check(long(previous, "locationReminderRequestedAt") != (fields["locationReminderRequestedAt"] as? Long) || long(previous, "locationReminderEpoch") == long(family, "epoch"))
                            fields + mapOf("locationReminderEpoch" to family.getJSONObject("epoch").getString("integerValue").toLong())
                        } else fields
                        val mask = scopedFields.keys.joinToString("&") { "updateMask.fieldPaths=$it" }
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
                        scopedFields.forEach { (key, value) -> jsonFields.put(key, encodeValue(value)) }
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
                                locationReminderResult = string(fields, "locationReminderResult"),
                                refreshEvidence=fields.keys().asSequence().associateWith{key->val value=fields.getJSONObject(key);when{value.has("integerValue")->value.getString("integerValue").toLongOrNull();value.has("doubleValue")->value.getDouble("doubleValue");value.has("booleanValue")->value.getBoolean("booleanValue");else->value.optString("stringValue","")}}
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
        withToken(onToken={token->submit({callback(null,"transport_busy")}){
            try {
                val all=LinkedHashMap<String,RemoteEvent>()
                fun page(reverse:Boolean,cursor:org.json.JSONArray?):Pair<List<JSONObject>,org.json.JSONArray?> {
                    val filters=org.json.JSONArray().put(JSONObject().put("fieldFilter",JSONObject().put("field",JSONObject().put("fieldPath","time")).put("op",if(reverse)"LESS_THAN" else "GREATER_THAN_OR_EQUAL").put("value",JSONObject().put("integerValue",dayStart.toString()))))
                    if(!reverse)filters.put(JSONObject().put("fieldFilter",JSONObject().put("field",JSONObject().put("fieldPath","time")).put("op","LESS_THAN").put("value",JSONObject().put("integerValue",dayEnd.toString()))))
                    val order=org.json.JSONArray()
                    for(field in listOf("time","__name__"))order.put(JSONObject().put("field",JSONObject().put("fieldPath",field)).put("direction",if(reverse)"DESCENDING" else "ASCENDING"))
                    val q=JSONObject().put("from",org.json.JSONArray().put(JSONObject().put("collectionId","events"))).put("where",JSONObject().put("compositeFilter",JSONObject().put("op","AND").put("filters",filters))).put("orderBy",order).put("limit",500)
                    if(cursor!=null)q.put("startAt",JSONObject().put("values",cursor).put("before",false))
                    val connection=(URL(DOC_URL+":runQuery").openConnection() as HttpURLConnection).apply{requestMethod="POST";connectTimeout=8_000;readTimeout=12_000;instanceFollowRedirects=false;doOutput=true;setRequestProperty("Authorization","Bearer $token");setRequestProperty("Content-Type","application/json")}
                    val docs=try{connection.outputStream.use{it.write(JSONObject().put("structuredQuery",q).toString().toByteArray(Charsets.UTF_8))}
                        check(connection.responseCode in 200..299){"history_page_unconfirmed"}
                        val rows=org.json.JSONArray(connection.inputStream.bufferedReader().use{it.readText()})
                        (0 until rows.length()).mapNotNull{rows.optJSONObject(it)?.optJSONObject("document")}
                    }finally{connection.disconnect()}
                    val last=docs.lastOrNull()
                    val next=last?.let{org.json.JSONArray().put(it.getJSONObject("fields").getJSONObject("time")).put(JSONObject().put("referenceValue",it.getString("name")))}
                    return docs to next
                }
                for(reverse in listOf(false,true)){
                    var cursor:org.json.JSONArray?=null
                    do {
                        val (docs,next)=page(reverse,cursor)
                        var anchor=false
                        for(doc in docs){val f=doc.getJSONObject("fields");val name=doc.getString("name");val type=string(f,"type")?:""
                            anchor=anchor || type in setOf("visit_start","trip_start")
                            all[name]=RemoteEvent(type,long(f,"time"),number(f,"lat"),number(f,"lon"),number(f,"accuracy"),string(f,"id")?:"",long(f,"offlineSince"),long(f,"durationMs"),name.substringAfterLast('/'))
                        }
                        if(docs.size<500 || (reverse && anchor))break
                        check(next!=null && next.toString()!=cursor?.toString()){"history_cursor_stalled"};cursor=next
                    }while(true)
                }
                post{callback(all.values.sortedWith(compareBy({it.time},{it.documentId})),null)}
            }catch(_:Exception){post{callback(null,"history_incomplete")}}
        }},onError={post{callback(null,it)}})
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
