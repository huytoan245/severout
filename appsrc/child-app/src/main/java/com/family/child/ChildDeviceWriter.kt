package com.family.child

import android.os.Handler
import android.os.Looper
import com.family.core.DeviceMutationPolicy
import com.family.core.ConfirmedWrite
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

/** Acknowledgements/latest GPS require authoritative, monotonic server writes. */
object ChildDeviceWriter {
    private val main = Handler(Looper.getMainLooper())
    fun write(fields: Map<String, Any?>, callback: ((Boolean) -> Unit)? = null) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: run { callback?.invoke(false); return }
        val completion = ConfirmedWrite({ done -> ChildHttpsBridge.patchGuarded(fields, uid) { ok -> main.post { done(ok) } } }) { callback?.invoke(it) }
        val db = FirebaseFirestore.getInstance()
        val doc = db.collection("devices").document("child-01")
        db.runTransaction { tx ->
            val remote = tx.get(doc)
            check(FirebaseAuth.getInstance().currentUser?.uid == uid) { "identity_changed" }
            if (DeviceMutationPolicy.confirmed(remote.data ?: emptyMap(), fields)) return@runTransaction true
            val selected = DeviceMutationPolicy.select(remote.data ?: emptyMap(), fields)
            if (selected.isEmpty()) return@runTransaction false
            if(fields.containsKey("locationTime") && !selected.containsKey("locationTime") && !selected.containsKey("refreshFixTime"))return@runTransaction false
            if (selected.isNotEmpty()) tx.set(doc, selected, SetOptions.merge())
            selected.containsKey("refreshCompletedFor") || selected.containsKey("refreshFailedFor") ||
                (!fields.containsKey("refreshCompletedFor") && !fields.containsKey("refreshFailedFor"))
        }.addOnSuccessListener { completion.primary(it) }
            .addOnFailureListener { completion.primary(false) }
        main.postDelayed({ completion.timeout() }, 8_000L)
    }
}
