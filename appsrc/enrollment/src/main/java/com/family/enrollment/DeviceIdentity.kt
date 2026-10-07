package com.family.enrollment

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec

internal object EnrollmentProof {
    fun base64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    fun message(uid: String, role: String, nonce: String, purpose: String, payload: String): ByteArray {
        val hash = base64(MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8)))
        return "FL232\nfamily-01\nchild-01\n$role\n$uid\n$nonce\n$purpose\n$hash".toByteArray(Charsets.UTF_8)
    }
    /** Android's ASN.1 DER ECDSA signature -> WebCrypto's fixed-width r || s. */
    fun rawSignature(der: ByteArray): ByteArray {
        require(der.size in 8..72 && der[0] == 0x30.toByte() && (der[1].toInt() and 255) == der.size - 2)
        var offset = 2
        fun integer(): ByteArray {
            require(offset + 2 <= der.size && der[offset++].toInt() == 2)
            val length = der[offset++].toInt() and 255
            require(length in 1..33 && offset + length <= der.size)
            val value = der.copyOfRange(offset, offset + length); offset += length
            require((value[0].toInt() and 128) == 0)
            val bytes = if (value.size > 1 && value[0].toInt() == 0) value.drop(1).toByteArray() else value
            require(bytes.size <= 32)
            return ByteArray(32 - bytes.size) + bytes
        }
        val raw = integer() + integer()
        require(offset == der.size)
        return raw
    }
}

internal object DeviceIdentity {
    // This is an installation key in Android Keystore, independent of the
    // unchanged release APK signing certificate/JKS. Never export private material.
    private const val ALIAS = "family-location-enrollment-v1"
    @Synchronized private fun entry(): KeyStore.PrivateKeyEntry {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(ALIAS)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256).setUserAuthenticationRequired(false).build())
            }.generateKeyPair()
        }
        return store.getEntry(ALIAS, null) as KeyStore.PrivateKeyEntry
    }
    fun publicKey(): String = EnrollmentProof.base64(entry().certificate.publicKey.encoded)
    fun sign(message: ByteArray): String {
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(entry().privateKey); signer.update(message)
        return EnrollmentProof.base64(EnrollmentProof.rawSignature(signer.sign()))
    }
}
