package com.family.child

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal object BoundedRest {
    fun call(url: String, token: String, body: JSONObject? = null): JSONObject? {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = if (body == null) "GET" else "PATCH"
            connectTimeout = 8_000; readTimeout = 8_000; instanceFollowRedirects = false
            useCaches = false; setRequestProperty("Connection", "close")
            setRequestProperty("Authorization", "Bearer $token"); setRequestProperty("Content-Type", "application/json")
        }
        try {
            if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) } }
            if (c.responseCode !in 200..299) return null
            return JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } finally { c.disconnect() }
    }
    fun encode(fields: Map<String, Any?>): JSONObject {
        val values = JSONObject()
        fields.forEach { (k, v) -> values.put(k, when(v) {
            null -> JSONObject().put("nullValue", "NULL_VALUE")
            is Boolean -> JSONObject().put("booleanValue", v)
            is Byte, is Short, is Int, is Long -> JSONObject().put("integerValue", v.toString())
            is Number -> JSONObject().put("doubleValue", v.toDouble())
            else -> JSONObject().put("stringValue", v.toString())
        }) }
        return JSONObject().put("fields", values)
    }
    fun decode(document: JSONObject): Map<String, Any?> {
        val fields = document.optJSONObject("fields") ?: return emptyMap()
        return fields.keys().asSequence().associateWith { k ->
            val v = fields.getJSONObject(k)
            when { v.has("integerValue") -> v.getString("integerValue").toLong()
                v.has("doubleValue") -> v.getDouble("doubleValue")
                v.has("booleanValue") -> v.getBoolean("booleanValue")
                v.has("stringValue") -> v.getString("stringValue")
                else -> null }
        }
    }
}
