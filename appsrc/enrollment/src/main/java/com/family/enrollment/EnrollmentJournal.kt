package com.family.enrollment

import android.content.SharedPreferences
import org.json.JSONObject

/** Commit before POST so another process instance can replay the exact proof. */
internal class EnrollmentJournal(private val prefs: SharedPreferences, private val role: String) {
    private val key = "pending_$role"
    fun read(uid: String, purpose: String, payload: String, now: Long): JSONObject? {
        val saved = prefs.getString(key, null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        if (saved.optString("uid") != uid || saved.optString("purpose") != purpose || saved.optString("payload") != payload ||
            saved.optLong("savedAt") > now || saved.optLong("savedAt") + 90_000L < now || saved.optJSONObject("proof") == null) return null
        return saved
    }
    fun save(value: JSONObject) { check(prefs.edit().putString(key, value.toString()).commit()) }
    fun clear() { check(prefs.edit().remove(key).commit()) }
}
