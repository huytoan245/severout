package com.family.enrollment

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal object EnrollmentManager {
    fun payload(material: String) = JSONObject().put("familyId", "family-01").put("deviceId", "child-01")
        .put("version", "2.3.2").put("deviceRecoveryMaterial", material)
    fun ensure(context: Context, base: String, role: String, bootstrap: String, force: Boolean, allowAuthReset: Boolean = true, allowKeyReset: Boolean = true): String {
        require(role in listOf("parent", "child"))
        val auth = FirebaseAuth.getInstance()
        val updateOnly=EnrollmentMode.updateOnly(context)
        val prefs = context.getSharedPreferences(EnrollmentClient.PREFS, Context.MODE_PRIVATE)
        if(updateOnly && auth.currentUser==null){
            check(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper()){"auth_wait_requires_worker"}
            val restored=AuthInitialization.await({auth.currentUser},{changed->
                val listener=FirebaseAuth.AuthStateListener{changed()};auth.addAuthStateListener(listener)
                val remove:()->Unit={auth.removeAuthStateListener(listener)};remove
            })
            if(restored==null){
                if(!DeviceIdentity.exists())throw EnrollmentFailure("device_key_missing",403)
                throw EnrollmentFailure("auth_init_timeout",503)
            }
        }
        // UPDATE_IN_PLACE never creates an identity/key or falls into recovery.
        if(updateOnly && !DeviceIdentity.exists())throw EnrollmentFailure("device_key_missing",403)
        if (auth.currentUser == null) {
            if(updateOnly)throw EnrollmentFailure("auth_init_timeout",503)
            Tasks.await(auth.signInAnonymously(), 20, TimeUnit.SECONDS)
        }
        val uid = auth.currentUser?.uid ?: throw EnrollmentFailure("no_auth", 503)
        val key = if(updateOnly)DeviceIdentity.existingPublicKey() else DeviceIdentity.publicKey()
        if (!force && EnrollmentStartup.cacheMatches(prefs.getInt("binding_protocol",0),prefs.getString("registered_$role",null),uid,prefs.getString("registered_key",null),key)) return uid
        val material = DeviceRecoveryBindingProvider.material(context, role)
        val result = try {
            if(updateOnly){
                EnrollmentClient.signed(context,base,role,"register",payload(material))
            } else if (EnrollmentJournal(prefs, role).pendingRebind(uid, System.currentTimeMillis())) {
                RebindManager.recover(context, base, role, material)
            } else try {
                EnrollmentClient.signed(context, base, role, "register", payload(material), bootstrap)
            } catch (e: EnrollmentFailure) {
                if (e.code == "slot_occupied") RebindManager.recover(context, base, role, material) else throw e
            }
        } catch (e: EnrollmentFailure) {
            // A restored/retired anonymous identity may get a fresh UID once.
            // The stable binding and new proof still decide server authorization.
            if (!updateOnly && e.code in listOf("retired_identity", "fresh_identity_required") && allowAuthReset) {
                prefs.edit().remove("registered_$role").remove("registered_key").commit()
                EnrollmentJournal(prefs, role).clear()
                auth.signOut()
                Tasks.await(auth.signInAnonymously(), 20, TimeUnit.SECONDS)
                return ensure(context, base, role, bootstrap, true, false, allowKeyReset)
            }
            if (!updateOnly && e.code == "fresh_key_required" && allowKeyReset) {
                prefs.edit().remove("registered_$role").remove("registered_key").commit()
                EnrollmentJournal(prefs, role).clear()
                DeviceIdentity.renewInstallationKey()
                return ensure(context, base, role, bootstrap, true, allowAuthReset, false)
            }
            if (e.status == 403 && !e.retryable) prefs.edit().putString("membership_status", "Recovery required").commit()
            throw e
        }
        check(result.optBoolean("registered") && result.optString("role") == role && auth.currentUser?.uid == uid)
        check(prefs.edit().putString("registered_$role", uid).putString("registered_key", key).putInt("binding_protocol", 232)
            .putLong("membership_epoch", result.getLong("epoch"))
            .putString("membership_status", if (result.optBoolean("rebound")) "Rebound" else "Registered").commit())
        return uid
    }
}
