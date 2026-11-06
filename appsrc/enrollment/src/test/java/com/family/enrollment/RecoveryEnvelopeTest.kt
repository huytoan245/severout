package com.family.enrollment

import android.util.Base64
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.MGF1ParameterSpec
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class RecoveryEnvelopeTest {
    private val pair=KeyPairGenerator.getInstance("RSA").apply{initialize(2048)}.generateKeyPair()
    private val pub get()=Base64.encodeToString(pair.public.encoded,Base64.NO_WRAP)
    private fun bytes(value:String)=Base64.decode(value,Base64.NO_WRAP)
    private fun permit(report:String="private-report",now:Long=1000)=JSONObject().put("scope","PARENT_ONLY_REBIND").put("reportHash",RecoveryEnvelope.hash(report))
        .put("bindingMatched",true).put("issuedAt",now).put("expiresAt",now+1000).put("epoch",3).put("generation",0)
        .put("revision","2026-11-06T08:00:00.123456Z").put("operationId","12345678-1234-1234-1234-123456789abc").put("oldParentUidHash",RecoveryEnvelope.hash("fixture-old")).put("materialHash",RecoveryEnvelope.hash("fixture-material"))
    private fun signed(value:JSONObject):String {
        val raw=value.toString().toByteArray()
        val sig=Signature.getInstance("SHA256withRSA").run{initSign(pair.private);update(raw);sign()}
        return JSONObject().put("payload",Base64.encodeToString(raw,Base64.NO_WRAP)).put("signature",Base64.encodeToString(sig,Base64.NO_WRAP)).toString()
    }
    @Test fun encryptedExportContainsNoPlaintextAndDecryptsOnlyForOperator(){
        val raw="fixture-private-uid-and-material";val envelope=RecoveryEnvelope.encrypt(raw,pub)
        assertFalse(envelope.contains(raw));val e=JSONObject(envelope)
        val aes=Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding").run{init(Cipher.DECRYPT_MODE,pair.private,OAEPParameterSpec("SHA-256","MGF1",MGF1ParameterSpec.SHA256,PSource.PSpecified.DEFAULT));doFinal(bytes(e.getString("wrappedKey")))}
        val result=Cipher.getInstance("AES/GCM/NoPadding").run{init(Cipher.DECRYPT_MODE,SecretKeySpec(aes,"AES"),GCMParameterSpec(128,bytes(e.getString("iv"))));updateAAD("FL233-REPORT-1".toByteArray());doFinal(bytes(e.getString("ciphertext")))}
        assertEquals(raw,String(result))
    }
    @Test fun encryptionRandomizesRepeatedReports(){assertNotEquals(RecoveryEnvelope.encrypt("report",pub),RecoveryEnvelope.encrypt("report",pub))}
    @Test fun exactSignedApprovalForReportIsAccepted(){assertEquals(3,RecoveryEnvelope.approval(signed(permit()),pub,"private-report",1500).getInt("epoch"))}
    @Test fun absentForgedOrWrongOperatorApprovalCannotAuthorize(){
        assertTrue(runCatching{RecoveryEnvelope.approval("{}",pub,"private-report",1500)}.isFailure)
        val altered=JSONObject(signed(permit()));altered.put("payload",Base64.encodeToString(permit().put("epoch",4).toString().toByteArray(),Base64.NO_WRAP))
        assertTrue(runCatching{RecoveryEnvelope.approval(altered.toString(),pub,"private-report",1500)}.isFailure)
        val other=KeyPairGenerator.getInstance("RSA").apply{initialize(2048)}.generateKeyPair()
        assertTrue(runCatching{RecoveryEnvelope.approval(signed(permit()),Base64.encodeToString(other.public.encoded,Base64.NO_WRAP),"private-report",1500)}.isFailure)
    }
    @Test fun changedReportExpiredFutureOrChildScopeStops(){
        assertTrue(runCatching{RecoveryEnvelope.approval(signed(permit()),pub,"other-phone-report",1500)}.isFailure)
        for(p in listOf(permit().put("scope","CHILD_REBIND"),permit().put("bindingMatched",false),permit().put("issuedAt",1600),permit().put("expiresAt",1400),permit().put("expiresAt",4_000_000)))
            assertTrue(runCatching{RecoveryEnvelope.approval(signed(p),pub,"private-report",1500)}.isFailure)
    }
    @Test fun malformedSnapshotGuardCannotBeApproved(){
        for(p in listOf(permit().put("epoch",0),permit().put("generation",-1),permit().put("revision","wrong"),permit().put("operationId","not-a-uuid")))
            assertTrue(runCatching{RecoveryEnvelope.approval(signed(p),pub,"private-report",1500)}.isFailure)
    }
}
