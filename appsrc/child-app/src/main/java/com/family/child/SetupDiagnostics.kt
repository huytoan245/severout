package com.family.child

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.location.LocationManagerCompat

/** Permission evidence reaches Parent even when a fresh Child cannot start its FGS. */
internal object SetupDiagnostics {
    fun fields(context: Context): Map<String, Any> {
        fun granted(permission: String) = ContextCompat.checkSelfPermission(context,permission) == PackageManager.PERMISSION_GRANTED
        return mapOf("fineLocationGranted" to granted(Manifest.permission.ACCESS_FINE_LOCATION),
            "backgroundLocationGranted" to (Build.VERSION.SDK_INT < 29 || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)),
            "notificationGranted" to (NotificationManagerCompat.from(context).areNotificationsEnabled() && (Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS))),
            "locationEnabled" to LocationManagerCompat.isLocationEnabled(context.getSystemService(LocationManager::class.java)),
            "batteryOptimizationIgnored" to RecoveryStarter.isBatteryOptimizationIgnored(context),
            "backgroundRestricted" to RecoveryStarter.isBackgroundRestricted(context),
            "diagnosticAt" to System.currentTimeMillis())
    }
}
