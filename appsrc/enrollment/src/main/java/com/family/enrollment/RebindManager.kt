package com.family.enrollment

import android.content.Context
import org.json.JSONObject

/** New UID/key proof plus stable recovery material; never carries bootstrap. */
internal object RebindManager {
    fun recover(context: Context, base: String, role: String, material: String): JSONObject =
        EnrollmentClient.signed(context, base, role, "rebind", EnrollmentManager.payload(material))
}
