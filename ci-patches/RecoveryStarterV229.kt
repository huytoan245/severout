package com.family.child

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat

/**
 * Centralized, evidence-based foreground-service recovery entry point.
 * It does not bypass Android restrictions. Callers use it only from legitimate
 * recovery paths (high-priority FCM, geofence, boot, battery-exempt watchdog).
 */
object RecoveryStarter {
    private const val PREFS = "tracking_diag"
    const val EXTRA_RECOVERY_REASON = "recovery_reason_v229"

    fun isBatteryOptimizationIgnored(context: Context): Boolean = try {
        Build.VERSION.SDK_INT < 23 ||
            context.getSystemService(PowerManager::class.java)
                .isIgnoringBatteryOptimizations(context.packageName)
    } catch (_: Exception) {
        false
    }

    fun isBackgroundRestricted(context: Context): Boolean = try {
        Build.VERSION.SDK_INT >= 28 &&
            context.getSystemService(ActivityManager::class.java).isBackgroundRestricted
    } catch (_: Exception) {
        false
    }

    fun startLocationService(
        context: Context,
        reason: String,
        refreshRequestId: Long = 0L
    ): Boolean {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        val fine = ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val background = Build.VERSION.SDK_INT < 29 ||
            ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        val locationEnabled = try {
            app.getSystemService(LocationManager::class.java).isLocationEnabled
        } catch (_: Exception) {
            false
        }
        val restricted = isBackgroundRestricted(app)
        val batteryExempt = isBatteryOptimizationIgnored(app)

        prefs.edit()
            .putLong("recovery_start_attempt_at_v229", now)
            .putString("recovery_start_reason_v229", reason)
            .putBoolean("recovery_start_fine_v229", fine)
            .putBoolean("recovery_start_background_v229", background)
            .putBoolean("recovery_start_location_enabled_v229", locationEnabled)
            .putBoolean("recovery_start_background_restricted_v229", restricted)
            .putBoolean("recovery_start_battery_exempt_v229", batteryExempt)
            .apply()

        if (!fine || !background || !locationEnabled || restricted) {
            prefs.edit()
                .putString("recovery_start_result_v229", "blocked_prerequisite")
                .apply()
            return false
        }

        val intent = Intent(app, LocationService::class.java)
            .putExtra(EXTRA_RECOVERY_REASON, reason)
            .putExtra("immediate", true)
        if (refreshRequestId > 0L) {
            intent.putExtra(LocationService.EXTRA_FCM_REFRESH_REQUEST_ID, refreshRequestId)
        }

        return try {
            ContextCompat.startForegroundService(app, intent)
            prefs.edit()
                .putString("recovery_start_result_v229", "requested")
                .putLong("recovery_start_requested_at_v229", now)
                .apply()
            true
        } catch (e: Exception) {
            prefs.edit()
                .putString("recovery_start_result_v229", "failed:${e.javaClass.simpleName}")
                .putLong("recovery_start_failed_at_v229", now)
                .apply()
            false
        }
    }
}
