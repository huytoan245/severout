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
    private val stages = setOf("refreshAckFor", "refreshReceivedFor", "refreshServiceFor", "refreshLocatingFor", "refreshValidatedFor", "refreshPersistedFor", "refreshUploadedFor", "refreshCompletedFor", "refreshFailedFor")
    fun select(remote: Map<String, Any?>, candidate: Map<String, Any?>): Map<String, Any?> {
        fun number(m: Map<String, Any?>, k: String) = (m[k] as? Number)?.toLong() ?: 0L
        val out = candidate.toMutableMap()
        val staleLocation = candidate.containsKey("locationTime") && number(candidate, "locationTime") < number(remote, "locationTime")
        if (staleLocation) locationKeys.forEach(out::remove)
        val request = stages.firstNotNullOfOrNull { (candidate[it] as? Number)?.toLong() }
        if (request != null) {
            if(stages.mapNotNull{(candidate[it] as? Number)?.toLong()}.distinct().size>1)return emptyMap()
            val current = number(remote, "refreshRequestedAt")
            val obsolete = request != current
            val terminal = number(remote, "refreshCompletedFor") == request || number(remote, "refreshFailedFor") == request
            val finishing = candidate.containsKey("refreshCompletedFor") || candidate.containsKey("refreshFailedFor")
            if (obsolete) return emptyMap()
            // A newer valid GPS may have won the CAS while this refresh was offline.
            // Confirm that authoritative fix without rewriting its coordinates.
            val newerServerFix=staleLocation && number(candidate,"locationTime")>=request && remote["lastLat"] is Number && remote["lastLon"] is Number
            if(candidate.containsKey("refreshCompletedFor") && newerServerFix)out["refreshFixTime"]=number(remote,"locationTime")
            val invalidSuccess = candidate.containsKey("refreshCompletedFor") && ((staleLocation && !newerServerFix) || number(candidate,"locationTime") < request || candidate["lastLat"] !is Number || candidate["lastLon"] !is Number)
            val contradictsTerminal = terminal && ((number(remote,"refreshCompletedFor")==request && candidate.containsKey("refreshFailedFor")) || (number(remote,"refreshFailedFor")==request && candidate.containsKey("refreshCompletedFor")))
            if (invalidSuccess || contradictsTerminal) return emptyMap()
            if (terminal && !finishing) out.keys.toList().filter { it.startsWith("refresh") || it == "lastError" }.forEach(out::remove)
        }
        return out
    }
    /** An empty selection is not success. Only an identical authoritative commit is idempotent success. */
    fun confirmed(remote: Map<String, Any?>, candidate: Map<String, Any?>): Boolean {
        val request=(candidate["refreshCompletedFor"] as? Number)?.toLong()
        if(request!=null && (remote["refreshRequestedAt"] as? Number)?.toLong()==request &&
            (remote["refreshCompletedFor"] as? Number)?.toLong()==request &&
            ((remote["refreshFixTime"] as? Number)?.toLong()?:0)>=((candidate["locationTime"] as? Number)?.toLong()?:Long.MAX_VALUE) &&
            ((candidate["locationTime"] as? Number)?.toLong()?:0)>=request &&
            ((remote["refreshFixTime"] as? Number)?.toLong()?:0)>=request)return true
        val values=candidate.filterValues { it == null || it is Number || it is String || it is Boolean }
        return values.isNotEmpty() && select(remote,candidate).isNotEmpty() && values.all { (k,v) ->
            val actual=remote[k]
            if(v is Number && actual is Number) v.toDouble()==actual.toDouble() else v==actual
        }
    }
}

object RecoveryPolicy {
    fun blockedReason(fine: Boolean, background: Boolean, enabled: Boolean, restricted: Boolean, foregroundRunning: Boolean = false): String? = when {
        !fine -> "permission_missing"
        !background && !foregroundRunning -> "background_permission_missing"
        !enabled -> "location_off"
        restricted -> "background_restricted"
        else -> null
    }
}
