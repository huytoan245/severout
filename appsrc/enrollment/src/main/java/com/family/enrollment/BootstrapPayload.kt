package com.family.enrollment

import org.json.JSONObject

/** Bootstrap is sent only for an empty role slot. Runtime and resume omit it. */
internal object BootstrapPayload {
    fun forChallenge(purpose: String, payload: JSONObject, challenge: JSONObject, token: String): JSONObject {
        val result = JSONObject(payload.toString())
        require(!result.has("bootstrap"))
        if (purpose == "register") {
            if (!challenge.has("needsBootstrap")) throw EnrollmentFailure("incompatible_backend", 503)
            if (challenge.getBoolean("needsBootstrap")) {
                if (!Regex("[A-Za-z0-9_-]{43}").matches(token)) throw EnrollmentFailure("bootstrap_not_provisioned", 403)
                result.put("bootstrap", token)
            }
        }
        return result
    }
}
