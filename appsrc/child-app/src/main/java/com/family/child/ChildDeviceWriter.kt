package com.family.child

import android.os.Handler
import android.os.Looper
import com.family.core.DeviceMutationPolicy
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

/** Acknowledgements/latest GPS require authoritative, monotonic server writes. */
object ChildDeviceWriter {
    private val main = Handler(Looper.getMainLooper())
    fun write(fields: Map<String, Any?>, callback: ((Boolean) -> Unit)? = null) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val db = FirebaseFirestore.getInstance()
        val doc = db.collection("devices").document("child-01")
        db.runTransaction { tx ->
            val remote = tx.get(doc)
            check(FirebaseAuth.getInstance().currentUser?.uid == uid) { "identity_changed" }
            val selected = DeviceMutationPolicy.select(remote.data ?: emptyMap(), fields)
            if (selected.isNotEmpty()) tx.set(doc, selected, SetOptions.merge())
            selected.containsKey("refreshCompletedFor") || selected.containsKey("refreshFailedFor") ||
                (!fields.containsKey("refreshCompletedFor") && !fields.containsKey("refreshFailedFor"))
        }.addOnSuccessListener { callback?.invoke(it) }
            .addOnFailureListener { callback?.invoke(false) }
        ChildHttpsBridge.patchGuarded(fields, uid) { ok -> main.post { callback?.invoke(ok) } }
    }
}
