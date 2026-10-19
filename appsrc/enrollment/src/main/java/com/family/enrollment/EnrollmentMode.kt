package com.family.enrollment

import android.content.Context
import android.content.pm.PackageManager

object EnrollmentMode {
    fun updateOnly(context: Context): Boolean = context.packageManager.getApplicationInfo(context.packageName,PackageManager.GET_META_DATA)
        .metaData?.getBoolean("family.update_in_place",false) ?: false
}
