package com.family.core

/** Protocol rules shared by real transports and deterministic regression tests. */
object WakeRequestPolicy {
    const val TTL = 15 * 60_000L
    fun parse(data: Map<String, String>, now: Long): Long? {
        val id = data["requestId"]?.toLongOrNull() ?: return null
        if (data["type"] != "location_refresh" || data["deviceId"] != "child-01" ||
            data["requestId"] != id.toString() || data["requestedAt"] != id.toString() ||
            data["expiresAt"]?.toLongOrNull() != id + TTL || id <= 0 || id > now + 30_000 || now >= id + TTL) return null
        return id
    }
}

object DeviceMutationPolicy {
    private val locationKeys = setOf("lastLat", "lastLon", "accuracy", "lastSeen", "locationTime", "lastSeenServer", "provider")
    private val stages = setOf("refreshAckFor", "refreshReceivedFor", "refreshServiceFor", "refreshLocatingFor", "refreshPersistedFor", "refreshUploadedFor", "refreshCompletedFor", "refreshFailedFor")
    fun select(remote: Map<String, Any?>, candidate: Map<String, Any?>): Map<String, Any?> {
        fun number(m: Map<String, Any?>, k: String) = (m[k] as? Number)?.toLong() ?: 0L
        val out = candidate.toMutableMap()
        if (candidate.containsKey("locationTime") && number(candidate, "locationTime") < number(remote, "locationTime")) locationKeys.forEach(out::remove)
        val request = stages.firstNotNullOfOrNull { (candidate[it] as? Number)?.toLong() }
        if (request != null) {
            val current = number(remote, "refreshRequestedAt")
            val obsolete = request != current
            val terminal = number(remote, "refreshCompletedFor") == request || number(remote, "refreshFailedFor") == request
            val finishing = candidate.containsKey("refreshCompletedFor") || candidate.containsKey("refreshFailedFor")
            if (obsolete || (terminal && !finishing)) out.keys.toList().filter { it.startsWith("refresh") || it == "lastError" }.forEach(out::remove)
            // An old failure/completion must not alter status attached to a newer request.
            if (obsolete) listOf("serviceState", "status").forEach(out::remove)
        }
        return out
    }
}

object RecoveryPolicy {
    fun blockedReason(fine: Boolean, background: Boolean, enabled: Boolean, restricted: Boolean): String? = when {
        !fine -> "permission_missing"
        !background -> "background_permission_missing"
        !enabled -> "location_off"
        restricted -> "background_restricted"
        else -> null
    }
}
