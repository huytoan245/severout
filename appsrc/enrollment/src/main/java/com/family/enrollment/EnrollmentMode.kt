package com.family.enrollment

import android.content.Context
import android.content.pm.PackageManager

object EnrollmentMode {
    fun recovery(context:Context):Boolean=context.packageName=="com.family.parent" &&
        context.packageManager.getApplicationInfo(context.packageName,PackageManager.GET_META_DATA).metaData?.getBoolean("family.parent_recovery",false)==true
    fun inspectionOnly(context:Context)=recovery(context) && !context.getSharedPreferences("parent_recovery_v233",Context.MODE_PRIVATE).getBoolean("completed",false)
    fun updateOnly(context: Context): Boolean = context.packageManager.getApplicationInfo(context.packageName,PackageManager.GET_META_DATA)
        .metaData?.getBoolean("family.update_in_place",false) ?: false
}
