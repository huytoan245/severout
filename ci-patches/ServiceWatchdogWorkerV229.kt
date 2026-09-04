package com.family.child

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * Secondary liveness watchdog. The main LocationService remains primary.
 * The watchdog only tries to resurrect the foreground service when Android's
 * prerequisites are satisfied and battery optimization has been exempted.
 */
class ServiceWatchdogWorker(
    appContext: Context,
    params: WorkerParameters
) : Worker(appContext, params) {

    override fun doWork(): Result {
        val app = applicationContext
        val now = System.currentTimeMillis()
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val trigger = inputData.getString(KEY_RECOVERY_REASON) ?: "periodic"
        val lastLocalHeartbeatAt = prefs.getLong(KEY_LAST_LOCAL_HEARTBEAT_AT, 0L)
        val stale = lastLocalHeartbeatAt <= 0L || now - lastLocalHeartbeatAt >= LOCAL_HEARTBEAT_STALE_MS
        val recoveryRequested = trigger != "periodic"

        UnusedAppRestrictionProbe.refresh(app)
        WakeTokenSyncWorker.ensurePeriodic(app)

        prefs.edit()
            .putLong(KEY_WATCHDOG_LAST_RUN_AT, now)
            .putBoolean(KEY_WATCHDOG_SAW_STALE_SERVICE, stale)
            .putString(KEY_WATCHDOG_TRIGGER, trigger)
            .apply()

        if (!stale && !recoveryRequested) {
            prefs.edit().putString(KEY_WATCHDOG_RESULT, "service_alive").apply()
            return Result.success()
        }

        val fine = ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val background = Build.VERSION.SDK_INT < 29 ||
            ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        val activity = app.getSystemService(ActivityManager::class.java)
        val backgroundRestricted = Build.VERSION.SDK_INT >= 28 && activity.isBackgroundRestricted
        val batteryExempt = RecoveryStarter.isBatteryOptimizationIgnored(app)

        prefs.edit()
            .putBoolean(KEY_BATTERY_OPTIMIZATION_IGNORED, batteryExempt)
            .putBoolean(KEY_BACKGROUND_RESTRICTED, backgroundRestricted)
            .apply()

        if (!fine || !background || backgroundRestricted) {
            prefs.edit().putString(KEY_WATCHDOG_RESULT, "blocked_permissions_or_restricted").apply()
            return Result.success()
        }

        // Android lists battery-optimization exemption as an allowed background
        // foreground-service start condition. Without it, repeated blind starts
        // only create ForegroundServiceStartNotAllowedException and waste battery.
        if (!batteryExempt) {
            prefs.edit().putString(KEY_WATCHDOG_RESULT, "waiting_battery_exemption").apply()
            return Result.success()
        }

        ProtectionNotifier.ensureVisible(app, "watchdog_$trigger")
        val started = RecoveryStarter.startLocationService(app, "watchdog_$trigger")
        return if (started) {
            prefs.edit()
                .putString(KEY_WATCHDOG_RESULT, "restart_requested")
                .putLong(KEY_WATCHDOG_RESTART_REQUESTED_AT, now)
                .apply()
            Result.success()
        } else {
            prefs.edit()
                .putString(KEY_WATCHDOG_RESULT, "restart_failed")
                .putLong(KEY_WATCHDOG_RESTART_FAILED_AT, now)
                .apply()
            if (recoveryRequested) Result.retry() else Result.success()
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
        const val KEY_WATCHDOG_TRIGGER = "watchdog_trigger"
        const val KEY_RECOVERY_REASON = "recovery_reason"
        const val KEY_BATTERY_OPTIMIZATION_IGNORED = "battery_optimization_ignored"
        const val KEY_BACKGROUND_RESTRICTED = "background_restricted"
        const val EXTRA_WATCHDOG_RECOVERY = "watchdog_recovery"

        private const val UNIQUE_PERIODIC_WORK = "family-location-service-watchdog"
        private const val UNIQUE_IMMEDIATE_WORK = "family-location-service-recovery"
        private const val LOCAL_HEARTBEAT_STALE_MS = 7 * 60_000L

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ServiceWatchdogWorker>(15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun requestImmediateRecovery(context: Context, reason: String) {
            val request = OneTimeWorkRequestBuilder<ServiceWatchdogWorker>()
                .setInputData(workDataOf(KEY_RECOVERY_REASON to reason))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                UNIQUE_IMMEDIATE_WORK,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
