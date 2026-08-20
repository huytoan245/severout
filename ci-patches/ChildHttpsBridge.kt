package com.family.child

import android.os.Handler
import android.os.Looper
import com.google.firebase.auth.FirebaseAuth
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** HTTPS fallback transport for Wi-Fi paths that break Firestore realtime channels. */
object ChildHttpsBridge {
    private const val DOC_URL = "https://firestore.googleapis.com/v1/projects/family-location-884e5/databases/(default)/documents/devices/child-01"
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    data class DeviceState(
        val refreshRequestedAt: Long = 0L,
        val refreshAckFor: Long = 0L,
        val refreshCompletedFor: Long = 0L,
        val refreshFailedFor: Long = 0L,
        val heartbeatAt: Long = 0L
    )

    fun patch(fields: Map<String, Any?>, callback: ((Boolean, String?) -> Unit)? = null) {
        withToken(
            onToken = { token ->
                executor.execute {
                    try {
                        val mask = fields.keys.joinToString("&") { "updateMask.fieldPaths=$it" }
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
                        val message = if (code in 200..299) null else readError(connection)
                        connection.disconnect()
                        callback?.let { cb -> post { cb(code in 200..299, message ?: if (code in 200..299) null else "HTTP $code") } }
                    } catch (e: Exception) {
                        callback?.let { cb -> post { cb(false, e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")) } }
                    }
                }
            },
            onError = { error -> callback?.let { cb -> post { cb(false, error) } } }
        )
    }

    fun readDevice(callback: (DeviceState?, String?) -> Unit) {
        withToken(
            onToken = { token ->
                executor.execute {
                    try {
                        val connection = (URL(DOC_URL).openConnection() as HttpURLConnection).apply {
                            requestMethod = "GET"
                            connectTimeout = 8_000
                            readTimeout = 8_000
                            setRequestProperty("Authorization", "Bearer $token")
                            setRequestProperty("Accept", "application/json")
                        }
                        val code = connection.responseCode
                        if (code in 200..299) {
                            val body = connection.inputStream.bufferedReader().use { it.readText() }
                            val fields = JSONObject(body).optJSONObject("fields") ?: JSONObject()
                            val state = DeviceState(
                                refreshRequestedAt = long(fields, "refreshRequestedAt"),
                                refreshAckFor = long(fields, "refreshAckFor"),
                                refreshCompletedFor = long(fields, "refreshCompletedFor"),
                                refreshFailedFor = long(fields, "refreshFailedFor"),
                                heartbeatAt = long(fields, "heartbeatAt")
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
            onError("no-auth")
            return
        }
        user.getIdToken(false)
            .addOnSuccessListener { result ->
                val token = result.token
                if (token.isNullOrBlank()) onError("no-id-token") else onToken(token)
            }
            .addOnFailureListener { e -> onError("token:${e.javaClass.simpleName}") }
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

    private fun readError(connection: HttpURLConnection): String? = try {
        connection.errorStream?.bufferedReader()?.use { it.readText() }?.take(400)
    } catch (_: Exception) { null }

    private fun post(block: () -> Unit) { main.post(block) }
}
