package com.family.child

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val allowedAction = intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        if (!allowedAction) return
        val reason=if(intent.action==Intent.ACTION_MY_PACKAGE_REPLACED)"package_replace" else "boot"
        val prefs=context.getSharedPreferences("tracking_diag",Context.MODE_PRIVATE)
        prefs.edit().putLong("bootObservedAt",System.currentTimeMillis()).putString("bootRecoveryResult","observed_$reason").apply()

        val pending = goAsync()
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ pending.finish() }, 8_000L)
        try {
            val requested=RecoveryStarter.startLocationService(context,reason)
            prefs.edit().putString("bootRecoveryResult",if(requested)"start_requested" else prefs.getString("recoveryErrorCode","boot_start_failed")).apply()
            if(!requested)ServiceWatchdogWorker.requestImmediateRecovery(context,"boot_start_failed")
        } catch (e: Exception) {
            context.getSharedPreferences("tracking_diag", Context.MODE_PRIVATE).edit()
                .putString("status", "restart_failed:${e.javaClass.simpleName}")
                .putString("bootRecoveryResult","boot_recovery_failed")
                .putLong("time", System.currentTimeMillis())
                .apply()
        }
        // Attempt the immediate boot exemption before scheduling secondary work.
        try{ServiceWatchdogWorker.schedule(context)}catch(_:Exception){}
        try{UnusedAppRestrictionProbe.refresh(context)}catch(_:Exception){}
        try{WakeTokenSyncWorker.schedule(context)}catch(_:Exception){}
        try{RecoveryGeofenceManager.rearmLatestObserved(context,reason)}catch(_:Exception){}
        RecoveryTelemetry.publish(context)
    }
}
