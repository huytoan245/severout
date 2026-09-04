package com.family.child

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * User-visible but silent protection notification used before a background wake.
 * It intentionally contains no location/GPS wording. A separate notification id
 * avoids replacing or timing out LocationService's foreground notification.
 */
object ProtectionNotifier {
    private const val CHANNEL_ID = "protection"
    private const val WAKE_NOTIFICATION_ID = 22942
    private const val PREFS = "tracking_diag"

    fun ensureVisible(context: Context, source: String): Boolean {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        val manager = app.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Protection",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                }
            )
        }

        val enabled = NotificationManagerCompat.from(app).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 26 || manager.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE)
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("protection_notification_enabled_v229", enabled)
            .putLong("protection_notification_checked_at_v229", now)
            .apply()
        if (!enabled) return false

        val openApp = PendingIntent.getActivity(
            app,
            229,
            Intent(app, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_guardian_shield_notification)
            .setContentTitle("Điện thoại của bạn đang được bảo vệ an toàn")
            .setContentIntent(openApp)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setTimeoutAfter(90_000L)
            .build()

        return try {
            manager.notify(WAKE_NOTIFICATION_ID, notification)
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong("protection_wake_notification_at_v229", now)
                .putString("protection_wake_notification_source_v229", source)
                .apply()
            true
        } catch (e: Exception) {
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("protection_wake_notification_result_v229", "failed:${e.javaClass.simpleName}")
                .putLong("protection_wake_notification_failed_at_v229", now)
                .apply()
            false
        }
    }
}
