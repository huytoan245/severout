package com.family.enrollment

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import java.security.MessageDigest

/** Practical same-user/device signal, not hardware attestation. Never log inputs/output. */
object DeviceRecoveryBindingProvider {
    const val RELEASE_SIGNER = "62b909ff3c5e6b56565cfe2814913778acdf694f32a042f12bc05c35404ed63f"
    fun material(context: Context, role: String): String {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: throw EnrollmentFailure("operator_recovery_required", 403)
        @Suppress("DEPRECATION")
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners
        } else context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
        if (signatures == null || signatures.size != 1) throw EnrollmentFailure("operator_recovery_required", 403)
        val signer = MessageDigest.getInstance("SHA-256").digest(signatures.single().toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0 && signer != RELEASE_SIGNER)
            throw EnrollmentFailure("operator_recovery_required", 403)
        return try { derive(androidId, role, context.packageName, signer) } catch (_: IllegalArgumentException) { throw EnrollmentFailure("operator_recovery_required", 403) }
    }
    internal fun derive(androidId: String, role: String, packageName: String, signer: String): String {
        require(role in listOf("parent", "child") && packageName == "com.family.$role")
        require(Regex("[a-fA-F0-9]{16}").matches(androidId) && androidId != "0000000000000000")
        require(Regex("[a-f0-9]{64}").matches(signer))
        val input = "FamilyLocation-v232\nfamily-01\n$role\n$packageName\n$signer\n${androidId.lowercase()}"
        return EnrollmentProof.base64(MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8)))
    }
}
