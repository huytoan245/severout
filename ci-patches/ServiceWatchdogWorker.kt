package com.family.child

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Secondary survival layer. The Location foreground service remains the primary
 * execution path; this periodic worker only attempts recovery when the local
 * service heartbeat has gone stale. It never bypasses Force Stop or user power
 * restrictions.
 */
class ServiceWatchdogWorker(
    appContext: Context,
    params: WorkerParameters
) : Worker(appContext, params) {

    override fun doWork(): Result {
        val now = System.currentTimeMillis()
        val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastLocalHeartbeatAt = prefs.getLong(KEY_LAST_LOCAL_HEARTBEAT_AT, 0L)
        val stale = lastLocalHeartbeatAt <= 0L || now - lastLocalHeartbeatAt >= LOCAL_HEARTBEAT_STALE_MS

        prefs.edit()
            .putLong(KEY_WATCHDOG_LAST_RUN_AT, now)
            .putBoolean(KEY_WATCHDOG_SAW_STALE_SERVICE, stale)
            .apply()

        if (!stale) return Result.success()

        val fine = ContextCompat.checkSelfPermission(
            applicationContext,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val background = Build.VERSION.SDK_INT < 29 || ContextCompat.checkSelfPermission(
            applicationContext,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val power = applicationContext.getSystemService(PowerManager::class.java)
        val batteryExempt = Build.VERSION.SDK_INT < 23 || power.isIgnoringBatteryOptimizations(applicationContext.packageName)
        val activity = applicationContext.getSystemService(ActivityManager::class.java)
        val backgroundRestricted = Build.VERSION.SDK_INT >= 28 && activity.isBackgroundRestricted

        if (!fine || !background || backgroundRestricted) {
            prefs.edit()
                .putString(KEY_WATCHDOG_RESULT, "blocked_by_permissions_or_background_restriction")
                .putBoolean(KEY_BATTERY_OPTIMIZATION_IGNORED, batteryExempt)
                .putBoolean(KEY_BACKGROUND_RESTRICTED, backgroundRestricted)
                .apply()
            return Result.success()
        }

        return try {
            val intent = Intent(applicationContext, LocationService::class.java)
                .putExtra(EXTRA_WATCHDOG_RECOVERY, true)
            ContextCompat.startForegroundService(applicationContext, intent)
            prefs.edit()
                .putString(KEY_WATCHDOG_RESULT, "restart_requested")
                .putLong(KEY_WATCHDOG_RESTART_REQUESTED_AT, now)
                .putBoolean(KEY_BATTERY_OPTIMIZATION_IGNORED, batteryExempt)
                .putBoolean(KEY_BACKGROUND_RESTRICTED, backgroundRestricted)
                .apply()
            Result.success()
        } catch (e: Exception) {
            prefs.edit()
                .putString(KEY_WATCHDOG_RESULT, "restart_failed:${e.javaClass.simpleName}")
                .putLong(KEY_WATCHDOG_RESTART_FAILED_AT, now)
                .apply()
            Result.success()
        }
    }

    companion object {
        const val PREFS = "tracking_diag"
        const val KEY_LAST_LOCAL_HEARTBEAT_AT = "last_local_service_heartbeat_at"
        const val KEY_WATCHDOG_LAST_RUN_AT = "watchdog_last_run_at"
        const val KEY_WATCHDOG_RESULT = "watchdog_result"
        const val KEY_WATCHDOG_SAW_STALE_SERVICE = "watchdog_saw_stale_service"
        const val KEY_WATCHDOG_RESTART_REQUESTED_AT = "watchdog_restart_requested_at"
        const val KEY_WATCHDOG_RESTART_FAILED_AT = "watchdog_restart_failed_at"
        const val KEY_BATTERY_OPTIMIZATION_IGNORED = "battery_optimization_ignored"
        const val KEY_BACKGROUND_RESTRICTED = "background_restricted"
        const val EXTRA_WATCHDOG_RECOVERY = "watchdog_recovery"

        private const val UNIQUE_WORK = "family-location-service-watchdog"
        private const val LOCAL_HEARTBEAT_STALE_MS = 7 * 60_000L

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ServiceWatchdogWorker>(15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                UNIQUE_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
