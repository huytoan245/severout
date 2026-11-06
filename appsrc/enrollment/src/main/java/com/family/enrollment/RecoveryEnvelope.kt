package com.family.enrollment

import android.util.Base64
import org.json.JSONObject
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

/** Encryption/approval key is an operator transport key, never an APK signer. */
object RecoveryEnvelope {
    fun hash(text:String)=EnrollmentProof.base64(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)))
    private fun bytes(text:String)=Base64.decode(text,Base64.NO_WRAP)
    private fun encode(value:ByteArray)=Base64.encodeToString(value,Base64.NO_WRAP)
    private fun key(publicKey:String)=KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(bytes(publicKey)))
    fun encrypt(report:String,publicKey:String):String {
        require(report.toByteArray().size<=16_384)
        val aes=KeyGenerator.getInstance("AES").apply{init(256)}.generateKey()
        val iv=ByteArray(12).also{SecureRandom().nextBytes(it)}
        val encrypted=Cipher.getInstance("AES/GCM/NoPadding").run{init(Cipher.ENCRYPT_MODE,aes,GCMParameterSpec(128,iv));updateAAD("FL233-REPORT-1".toByteArray());doFinal(report.toByteArray(Charsets.UTF_8))}
        val wrapped=Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding").run{
            init(Cipher.ENCRYPT_MODE,key(publicKey),OAEPParameterSpec("SHA-256","MGF1",MGF1ParameterSpec.SHA256,PSource.PSpecified.DEFAULT));doFinal(aes.encoded)
        }
        return JSONObject().put("format","FL233-REPORT-1").put("wrappedKey",encode(wrapped)).put("iv",encode(iv)).put("ciphertext",encode(encrypted)).toString()
    }
    fun approval(envelope:String,publicKey:String,report:String,now:Long):JSONObject {
        require(envelope.length<=16_384)
        val wrapped=JSONObject(envelope);val raw=bytes(wrapped.getString("payload"));require(raw.size<=8192)
        val verified=Signature.getInstance("SHA256withRSA").run{initVerify(key(publicKey));update(raw);verify(bytes(wrapped.getString("signature")))}
        require(verified){"approval_signature_invalid"}
        val value=JSONObject(String(raw,Charsets.UTF_8))
        require(value.getString("scope")=="PARENT_ONLY_REBIND" && value.getString("reportHash")==hash(report) && value.getBoolean("bindingMatched")){"approval_scope_invalid"}
        require(value.getLong("issuedAt")<=now && value.getLong("expiresAt")>now && value.getLong("expiresAt")-value.getLong("issuedAt")<=3_600_000){"approval_expired"}
        require(value.getLong("epoch") in 1..9_007_199_254_740_990L && value.getLong("generation") in 0..9_007_199_254_740_990L)
        require(Regex("[0-9T:.Z-]{20,40}").matches(value.getString("revision")))
        require(Regex("[a-f0-9-]{36}").matches(value.getString("operationId")))
        require(Regex("[A-Za-z0-9_-]{43}").matches(value.getString("oldParentUidHash")))
        require(Regex("[A-Za-z0-9_-]{43}").matches(value.getString("materialHash")))
        return value
    }
}
