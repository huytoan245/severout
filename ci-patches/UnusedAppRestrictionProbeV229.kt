package com.family.child

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.core.content.PackageManagerCompat
import androidx.core.content.UnusedAppRestrictionsConstants

/** Records Android unused-app / hibernation status without showing UI. */
object UnusedAppRestrictionProbe {
    private const val PREFS = "tracking_diag"

    fun refresh(context: Context) {
        val app = context.applicationContext
        val future = PackageManagerCompat.getUnusedAppRestrictionsStatus(app)
        future.addListener({
            val now = System.currentTimeMillis()
            try {
                val status = future.get()
                val enabled = status == UnusedAppRestrictionsConstants.API_31 ||
                    status == UnusedAppRestrictionsConstants.API_30 ||
                    status == UnusedAppRestrictionsConstants.API_30_BACKPORT
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putInt("unused_app_restrictions_status_v229", status)
                    .putBoolean("unused_app_restrictions_enabled_v229", enabled)
                    .putLong("unused_app_restrictions_checked_at_v229", now)
                    .apply()
            } catch (e: Exception) {
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putInt("unused_app_restrictions_status_v229", UnusedAppRestrictionsConstants.ERROR)
                    .putBoolean("unused_app_restrictions_enabled_v229", true)
                    .putString("unused_app_restrictions_error_v229", e.javaClass.simpleName)
                    .putLong("unused_app_restrictions_checked_at_v229", now)
                    .apply()
            }
        }, ContextCompat.getMainExecutor(app))
    }
}
