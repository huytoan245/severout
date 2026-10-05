package com.family.enrollment

import android.content.Context
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EnrollmentJournalTest {
    private fun prefs() = RuntimeEnvironment.getApplication().getSharedPreferences("journal-test", Context.MODE_PRIVATE)
    private fun value() = JSONObject().put("uid", "retained-uid").put("purpose", "register").put("payload", "payload")
        .put("savedAt", 1000L).put("proof", JSONObject().put("nonce", "server-nonce").put("signature", "same-signature"))
    @Test fun retryAfterColdClientInstanceKeepsExactProof() {
        prefs().edit().clear().commit()
        EnrollmentJournal(prefs(), "child").save(value())
        val cold = EnrollmentJournal(prefs(), "child")
        assertEquals("same-signature", cold.read("retained-uid", "register", "payload", 2000L)!!.getJSONObject("proof").getString("signature"))
        assertNull(EnrollmentJournal(prefs(), "parent").read("retained-uid", "register", "payload", 2000L))
        cold.clear()
        assertNull(cold.read("retained-uid", "register", "payload", 2000L))
    }
    @Test fun identityOperationRotationExpiryAndClockRollbackCannotReuseProof() {
        val journal = EnrollmentJournal(prefs(), "child"); journal.save(value())
        assertNull(journal.read("new-uid", "register", "payload", 2000L))
        assertNull(journal.read("retained-uid", "token", "payload", 2000L))
        assertNull(journal.read("retained-uid", "register", "new-token-payload", 2000L))
        assertNull(journal.read("retained-uid", "register", "payload", 91001L))
        assertNull(journal.read("retained-uid", "register", "payload", 999L))
    }
    @Test fun truncatedPreferencesFailClosedAndRetryableFailuresAreExplicit() {
        prefs().edit().putString("pending_child", "{truncated").commit()
        assertNull(EnrollmentJournal(prefs(), "child").read("retained-uid", "register", "payload", 2000L))
        for (status in listOf(401, 409, 429, 503)) assertTrue(EnrollmentFailure("retry", status).retryable)
        assertFalse(EnrollmentFailure("slot_occupied", 403).retryable)
        assertTrue(EnrollmentFailure("invalid_nonce", 403).retryable)
    }
}
