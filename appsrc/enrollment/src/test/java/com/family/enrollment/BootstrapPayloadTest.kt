package com.family.enrollment

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.SecureRandom
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BootstrapPayloadTest {
    private fun token() = ByteArray(32).also { SecureRandom().nextBytes(it) }.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    @Test fun firstClaimIncludesOnlyTheRoleInput() {
        val token = token(); val payload = JSONObject().put("version", "2.3.1")
        val result = BootstrapPayload.forChallenge("register", payload, JSONObject().put("needsBootstrap", true), token)
        assertTrue(result.optString("bootstrap") == token); assertFalse(payload.has("bootstrap"))
    }
    @Test fun resumeAndRuntimeNeverIncludeBootstrap() {
        for (purpose in listOf("register", "wake", "token")) {
            assertFalse(BootstrapPayload.forChallenge(purpose, JSONObject(), JSONObject().put("needsBootstrap", false), token()).has("bootstrap"))
        }
    }
    @Test fun unprovisionedFirstClaimAndOldBackendFailClosed() {
        try { BootstrapPayload.forChallenge("register", JSONObject(), JSONObject().put("needsBootstrap", true), ""); fail() }
        catch (e: EnrollmentFailure) { assertEquals("bootstrap_not_provisioned", e.code) }
        try { BootstrapPayload.forChallenge("register", JSONObject(), JSONObject(), token()); fail() }
        catch (e: EnrollmentFailure) { assertEquals("incompatible_backend", e.code) }
    }
    @Test fun callerCannotSmuggleBootstrapIntoRuntimePayload() {
        try { BootstrapPayload.forChallenge("wake", JSONObject().put("bootstrap", token()), JSONObject(), ""); fail() }
        catch (_: IllegalArgumentException) { }
    }
}
