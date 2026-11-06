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
import com.family.core.RecoveryV233
import com.family.core.RecoveryConditions

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
            androidx.core.location.LocationManagerCompat.isLocationEnabled(app.getSystemService(LocationManager::class.java))
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

        val existingForeground = LocationService.running && now-prefs.getLong(ServiceWatchdogWorker.KEY_LAST_LOCAL_HEARTBEAT_AT,0L) in 0L..150_000L
        val source=RecoveryV233.source(reason)
        val blocked = RecoveryV233.blocked(source,RecoveryConditions(fine,background,locationEnabled,restricted,batteryExempt,existingForeground,Build.VERSION.SDK_INT))
        prefs.edit().putLong("recoveryAttemptAt",now).putString("recoveryResult","checking").apply()
        if (blocked != null) {
            prefs.edit()
                .putString("recovery_start_result_v229", "blocked:$blocked")
                .putString("recoveryResult","blocked").putString("recoveryErrorCode",blocked)
                .apply()
            RecoveryTelemetry.publish(app)
            RefreshStatus.progress(app,refreshRequestId,com.family.core.RefreshStage.FAILED,blocked.uppercase(),"Không thể khởi chạy dịch vụ: $blocked")
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
                .putString("recoveryResult","start_requested").putString("recoveryErrorCode","")
                .apply()
            try{ServiceWatchdogWorker.verifyStart(app,now)}catch(_:Exception){prefs.edit().putString("recoveryErrorCode","verification_schedule_failed").apply()}
            true
        } catch (e: Exception) {
            val code=if(e is SecurityException)"fgs_security_exception" else if(e.javaClass.simpleName=="ForegroundServiceStartNotAllowedException")"fgs_start_not_allowed" else "fgs_start_failed"
            prefs.edit()
                .putString("recovery_start_result_v229", "failed:${e.javaClass.simpleName}")
                .putLong("recovery_start_failed_at_v229", now)
                .putString("recoveryResult","rejected").putString("recoveryErrorCode",code)
                .apply()
            RecoveryTelemetry.publish(app)
            RefreshStatus.progress(app,refreshRequestId,com.family.core.RefreshStage.FAILED,"FGS_START_REJECTED","Android từ chối khởi chạy dịch vụ")
            false
        }
    }
}
