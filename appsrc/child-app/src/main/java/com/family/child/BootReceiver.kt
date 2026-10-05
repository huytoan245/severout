package com.family.child

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val allowedAction = intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        if (!allowedAction) return

        val pending = goAsync()
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ pending.finish() }, 8_000L)
        ServiceWatchdogWorker.schedule(context)
        UnusedAppRestrictionProbe.refresh(context)
        WakeTokenSyncWorker.schedule(context)
        RecoveryGeofenceManager.rearmLatestObserved(context, if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) "package_replace" else "boot")

        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val background = Build.VERSION.SDK_INT < 29 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (!fine || !background) {
            context.getSharedPreferences("tracking_diag", Context.MODE_PRIVATE).edit()
                .putString("status", "restart_needs_location_permission")
                .putLong("time", System.currentTimeMillis())
                .apply()
            return
        }

        try {
            if (!RecoveryStarter.startLocationService(context, if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) "package_replace" else "boot")) ServiceWatchdogWorker.requestImmediateRecovery(context, "boot_start_failed")
        } catch (e: Exception) {
            context.getSharedPreferences("tracking_diag", Context.MODE_PRIVATE).edit()
                .putString("status", "restart_failed:${e.javaClass.simpleName}")
                .putLong("time", System.currentTimeMillis())
                .apply()
        }
    }
}
