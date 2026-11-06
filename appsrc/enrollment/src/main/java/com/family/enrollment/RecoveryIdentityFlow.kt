package com.family.enrollment

internal data class RecoveryIdentityRecord(val started:Boolean=false,val originalUid:String?=null,val newUid:String?=null,val keyRequested:Boolean=false,val newKey:String?=null)
/** Persist intent before each irreversible SDK call. A timeout is not permission to repeat it. */
internal class RecoveryIdentityFlow(
    private val read:()->RecoveryIdentityRecord,private val save:(RecoveryIdentityRecord)->Unit,
    private val uid:()->String?,private val keyExists:()->Boolean,private val existingKey:()->String,
    private val createAuth:()->Unit,private val createKey:()->String
) {
    fun resume(inspectedUid:String?,oldParentUidHash:String):Pair<String,String>{
        var state=read()
        if(!state.started){
            check(!keyExists()){"existing_key_preserved"}
            check(uid()==inspectedUid){"auth_changed_since_inspection"}
            state=state.copy(started=true,originalUid=inspectedUid);save(state)
            createAuth()
        }
        val current=uid()?:error("auth_creation_unresolved_stop")
        check(current!=state.originalUid && RecoveryEnvelope.hash(current)!=oldParentUidHash){"fresh_identity_not_confirmed"}
        check(state.newUid==null || state.newUid==current){"identity_changed"}
        if(state.newUid==null){state=state.copy(newUid=current);save(state)}
        if(!state.keyRequested){
            check(!keyExists()){"existing_key_preserved"}
            state=state.copy(keyRequested=true);save(state)
        }
        val key=if(state.newKey!=null){check(keyExists()){"key_changed"};existingKey()}else if(keyExists())existingKey() else createKey()
        check(state.newKey==null||state.newKey==key){"key_changed"}
        if(state.newKey==null){state=state.copy(newKey=key);save(state)}
        return current to key
    }
}
