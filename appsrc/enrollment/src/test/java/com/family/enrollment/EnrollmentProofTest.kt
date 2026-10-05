package com.family.enrollment

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EnrollmentProofTest {
    @Test fun canonicalProofBindsUidRoleNoncePurposeAndPayload() {
        val payload = "{\"familyId\":\"family-01\",\"deviceId\":\"child-01\",\"version\":\"2.3.1\"}"
        val original = EnrollmentProof.message("uid", "child", "nonce", "register", payload)
        assertTrue(String(original).startsWith("FL231\nfamily-01\nchild-01\nchild\nuid\nnonce\nregister\n"))
        for (changed in listOf(
            EnrollmentProof.message("other", "child", "nonce", "register", payload),
            EnrollmentProof.message("uid", "parent", "nonce", "register", payload),
            EnrollmentProof.message("uid", "child", "other", "register", payload),
            EnrollmentProof.message("uid", "child", "nonce", "wake", payload),
            EnrollmentProof.message("uid", "child", "nonce", "register", payload + " ")
        )) assertFalse(original.contentEquals(changed))
    }
    @Test fun androidDerSignaturesVerifyAfterWebCryptoConversion() {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        repeat(40) { index ->
            val message = "nonce-$index".toByteArray()
            val der = Signature.getInstance("SHA256withECDSA").apply { initSign(pair.private); update(message) }.sign()
            val raw = EnrollmentProof.rawSignature(der)
            assertEquals(64, raw.size)
            val verifier = Signature.getInstance("SHA256withECDSAinP1363Format").apply { initVerify(pair.public); update(message) }
            assertTrue(verifier.verify(raw))
        }
    }
    @Test fun malformedDerIsRejected() {
        for (bytes in listOf(ByteArray(0), byteArrayOf(0x30, 6, 2, 1, -1, 2, 1, 1), byteArrayOf(0x30, 6, 2, 2, 1, 2, 1, 1))) {
            assertTrue(runCatching { EnrollmentProof.rawSignature(bytes) }.isFailure)
        }
    }
    @Test fun publicEncodingMatchesBackendBase64Url() {
        assertEquals("-_8", EnrollmentProof.base64(byteArrayOf(-5, -1)))
    }
}
