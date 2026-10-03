package com.family.child

/** Public UID only, once per process. Used when old exact-UID rules block provisioning. */
internal object IdentityDiagnostics {
    private var observed: String? = null
    @Synchronized fun record(uid: String) {
        if (observed == uid) return
        observed = uid
        android.util.Log.i("FamilyIdentity", "role=child uid=$uid")
    }
}
