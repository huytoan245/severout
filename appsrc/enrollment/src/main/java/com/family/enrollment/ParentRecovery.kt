package com.family.enrollment

import android.content.Context
import android.os.Looper
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Separate, manual Parent recovery. No startup path creates Auth users or keys. */
object ParentRecovery {
    data class Inspection(val auth:String,val key:String,val usable:String,val code:String)
    private fun prefs(c:Context)=c.getSharedPreferences("parent_recovery_v233",Context.MODE_PRIVATE)
    private fun checkMode(c:Context){
        check(EnrollmentMode.recovery(c) && Looper.myLooper()!=Looper.getMainLooper()){"recovery_mode_required"}
        check(c.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE==0){"release_recovery_required"}
    }
    private fun waitAuth():com.google.firebase.auth.FirebaseUser? {
        val auth=FirebaseAuth.getInstance()
        return AuthInitialization.await({auth.currentUser},{changed->
            val listener=FirebaseAuth.AuthStateListener{changed()};auth.addAuthStateListener(listener)
            val remove:()->Unit={auth.removeAuthStateListener(listener)};remove
        })
    }
    fun inspect(c:Context):Inspection {
        checkMode(c)
        val user=waitAuth()
        val exists=try{DeviceIdentity.exists()}catch(_:Exception){return Inspection(if(user==null)"NOT_OBSERVED_AFTER_WAIT" else "PRESENT","UNKNOWN","UNKNOWN","keystore_read_failed")}
        val usable=if(exists)try{DeviceIdentity.verifyExistingKey()}catch(_:Exception){false}else false
        val material=DeviceRecoveryBindingProvider.material(c,"parent")
        val raw=JSONObject().put("format","FL233-PARENT-INSPECTION-1").put("role","parent").put("package","com.family.parent")
            .put("signer",DeviceRecoveryBindingProvider.RELEASE_SIGNER).put("collectedAt",System.currentTimeMillis()).put("reportId",UUID.randomUUID().toString())
            .put("uid",user?.uid?:JSONObject.NULL).put("keyPresent",exists).put("keyUsable",usable)
            .put("publicKey",if(usable)DeviceIdentity.existingPublicKey() else JSONObject.NULL).put("material",material).toString()
        // Preserve the exact approved report across retries/process death.
        if(!prefs(c).contains("phase") && !prefs(c).contains("approval"))check(prefs(c).edit().putString("report",raw).commit())
        return Inspection(if(user==null)"NOT_OBSERVED_AFTER_WAIT" else "PRESENT",if(exists)"PRESENT" else "ABSENT",if(exists)usable.toString() else "N/A",if(!exists)"device_key_missing" else if(!usable)"key_unusable" else "existing_key_preserved")
    }
    fun export(c:Context,operatorKey:String):String {
        checkMode(c);val raw=prefs(c).getString("report",null)?:error("inspect_first")
        return RecoveryEnvelope.encrypt(raw,operatorKey)
    }
    fun importApproval(c:Context,operatorKey:String,envelope:String){
        checkMode(c);val raw=prefs(c).getString("report",null)?:error("inspect_first")
        val approval=RecoveryEnvelope.approval(envelope,operatorKey,raw,System.currentTimeMillis())
        val report=JSONObject(raw)
        check(approval.getString("materialHash")==RecoveryEnvelope.hash(report.getString("material"))){"approval_scope_invalid"}
        check(!report.getBoolean("keyPresent")){"existing_key_preserved"}
        check(RecoveryEnvelope.hash(DeviceRecoveryBindingProvider.material(c,"parent"))==RecoveryEnvelope.hash(report.getString("material"))){"device_changed"}
        val old=prefs(c).getString("approval",null)
        check(old==null || JSONObject(old).getString("operationId")==approval.getString("operationId")){"operation_already_prepared"}
        check(prefs(c).edit().putString("approval",approval.toString()).putString("signedApproval",envelope).putString("operatorKeyHash",RecoveryEnvelope.hash(operatorKey)).commit())
    }
    private fun health(base:String):JSONObject {
        val url=URL(base);require(url.protocol=="https" && url.host=="family-location-wake.huytoan0979928450.workers.dev" && url.port in listOf(-1,443) && url.userInfo==null && url.query==null && url.ref==null && (url.path.isEmpty()||url.path=="/"))
        val connection=URL(base.trimEnd('/')+"/health").openConnection() as HttpURLConnection
        return try{connection.connectTimeout=8000;connection.readTimeout=8000;connection.instanceFollowRedirects=false
            check(connection.responseCode==200){"backend_health_failed"}
            val raw=connection.inputStream.use{input->val bytes=java.io.ByteArrayOutputStream();val buffer=ByteArray(1024)
                while(true){val n=input.read(buffer);if(n<0)break;require(bytes.size()+n<=8192);bytes.write(buffer,0,n)};bytes.toByteArray()};JSONObject(String(raw,Charsets.UTF_8))
        }finally{connection.disconnect()}
    }
    @Synchronized fun applyApproved(c:Context,base:String){
        checkMode(c);val p=prefs(c);val reportRaw=p.getString("report",null)?:error("inspect_first");val report=JSONObject(reportRaw)
        val approval=JSONObject(p.getString("approval",null)?:error("separate_operator_approval_required"))
        val expired=approval.getLong("expiresAt")<=System.currentTimeMillis()
        check(!expired || (p.getBoolean("postAttempted",false) && p.contains("newUid") && p.contains("newKey"))){ "approval_expired" }
        check(approval.getString("reportHash")==RecoveryEnvelope.hash(reportRaw)){"approval_scope_invalid"}
        check(DeviceRecoveryBindingProvider.material(c,"parent")==report.getString("material")){"device_changed"}
        if(!expired){val health=health(base);check(health.optInt("parentRecoveryGuard")==1 && health.optBoolean("configured") && health.optString("parentRecoveryOperatorKeyHash")==p.getString("operatorKeyHash",null)){"backend_guard_required"}}
        val auth=FirebaseAuth.getInstance();waitAuth()
        check(!report.getBoolean("keyPresent")){"existing_key_preserved"}
        val identity=if(expired){
            check(auth.currentUser?.uid==p.getString("newUid",null) && DeviceIdentity.exists() && DeviceIdentity.existingPublicKey()==p.getString("newKey",null)){"identity_changed"}
            p.getString("newUid",null)!! to p.getString("newKey",null)!!
        }else RecoveryIdentityFlow(
            {RecoveryIdentityRecord(p.contains("phase"),p.getString("originalUid",null)?.takeIf{it.isNotEmpty()},p.getString("newUid",null),p.getBoolean("keyRequested",false),p.getString("newKey",null))},
            {record->check(p.edit().putString("phase","PREPARING").putString("originalUid",record.originalUid?:"").putString("newUid",record.newUid)
                .putBoolean("keyRequested",record.keyRequested).putString("newKey",record.newKey).commit())},
            {auth.currentUser?.uid},DeviceIdentity::exists,DeviceIdentity::existingPublicKey,
            {auth.signOut();Tasks.await(auth.signInAnonymously(),20,TimeUnit.SECONDS);Unit},DeviceIdentity::publicKey
        ).resume(if(report.isNull("uid"))null else report.getString("uid"),approval.getString("oldParentUidHash"))
        val user=auth.currentUser?:error("auth_creation_unresolved_stop");check(user.isAnonymous && user.uid==identity.first){"identity_changed"}
        val publicKey=identity.second
        val guard=JSONObject().put("revision",approval.getString("revision")).put("epoch",approval.getLong("epoch"))
            .put("generation",approval.getLong("generation")).put("operationId",approval.getString("operationId"))
        val header=EnrollmentProof.base64(guard.toString().toByteArray(Charsets.UTF_8))
        val signedApproval=EnrollmentProof.base64((p.getString("signedApproval",null)?:error("separate_operator_approval_required")).toByteArray(Charsets.UTF_8))
        // A lost POST response can outlive the nonce. Existing new UID+key must
        // prove current membership before treating that operation as complete.
        if(p.getBoolean("postAttempted",false)){
            try{
                val state=EnrollmentClient.call(base,"/v1/family",JSONObject(),user.uid,get=true)
                check(state.optString("role")=="parent" && state.getLong("epoch")==approval.getLong("epoch")+1){"recovery_state_changed"}
                val ch=EnrollmentClient.call(base,"/v1/challenge",JSONObject().put("familyId","family-01").put("deviceId","child-01").put("role","parent").put("purpose","register"),user.uid)
                val raw=EnrollmentManager.payload(report.getString("material")).toString()
                val proof=proof(user.uid,ch.getString("nonce"),"register",raw,publicKey)
                val result=EnrollmentClient.call(base,"/v1/register/parent",proof,user.uid)
                complete(c,user.uid,publicKey,result,approval);return
            }catch(e:EnrollmentFailure){if(e.code!="role_not_registered")throw e}
        }
        check(!expired){"approval_expired"} // Expired approval permits reconciliation only, never a new rebind.
        var pending=p.getString("proof",null)?.let{JSONObject(it)}
        if(pending==null || p.getLong("nonceExpires",0)<=System.currentTimeMillis()){
            val challenge=EnrollmentClient.call(base,"/v1/challenge",JSONObject().put("familyId","family-01").put("deviceId","child-01").put("role","parent").put("purpose","rebind"),user.uid,header,signedApproval)
            check(challenge.optString("recoveryGuardHash")==RecoveryEnvelope.hash(guard.toString()) && challenge.getLong("epoch")==approval.getLong("epoch") && challenge.getLong("generation")==approval.getLong("generation")){"backend_guard_not_confirmed"}
            pending=proof(user.uid,challenge.getString("nonce"),"rebind",EnrollmentManager.payload(report.getString("material")).toString(),publicKey)
            check(p.edit().putString("proof",pending.toString()).putLong("nonceExpires",challenge.getLong("expires")).commit())
        }
        check(p.edit().putBoolean("postAttempted",true).commit())
        val result=EnrollmentClient.call(base,"/v1/rebind/parent",pending,user.uid,header,signedApproval)
        complete(c,user.uid,publicKey,result,approval)
    }
    private fun proof(uid:String,nonce:String,purpose:String,raw:String,key:String)=JSONObject().put("nonce",nonce).put("publicKey",key).put("payload",raw)
        .put("signature",DeviceIdentity.sign(EnrollmentProof.message(uid,"parent",nonce,purpose,raw),true))
    private fun complete(c:Context,uid:String,key:String,result:JSONObject,approval:JSONObject){
        check(result.optBoolean("registered") && result.optString("role")=="parent" && result.getLong("epoch")==approval.getLong("epoch")+1){"recovery_result_invalid"}
        check(FirebaseAuth.getInstance().currentUser?.uid==uid && DeviceIdentity.existingPublicKey()==key){"identity_changed"}
        check(c.getSharedPreferences(EnrollmentClient.PREFS,Context.MODE_PRIVATE).edit().putString("registered_parent",uid).putString("registered_key",key)
            .putInt("binding_protocol",232).putLong("membership_epoch",result.getLong("epoch")).putString("membership_status","Rebound").commit())
        check(prefs(c).edit().putBoolean("completed",true).putString("phase","COMPLETE").commit())
    }
}
