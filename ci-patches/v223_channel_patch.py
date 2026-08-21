from pathlib import Path


def read(path):
    p = Path(path)
    if not p.exists():
        raise SystemExit(f'missing file: {p}')
    return p, p.read_text(encoding='utf-8')


def replace_once(path, old, new):
    p, text = read(path)
    if old not in text:
        raise SystemExit(f'anchor not found in {path}: {old[:100]!r}')
    p.write_text(text.replace(old, new, 1), encoding='utf-8')


loc = 'appsrc/child-app/src/main/java/com/family/child/LocationService.kt'
parent = 'appsrc/parent-app/src/main/java/com/family/parent/MainActivity.kt'

# Use named channel constants so permission/channel checks and notification creation
# always refer to the exact same Android channels.
replace_once(loc, '        val id = "protection"', '        val id = PROTECTION_CHANNEL_ID')
replace_once(loc, '        val channelId = "family_location_reminder"', '        val channelId = REMINDER_CHANNEL_ID')

# Before reporting a Parent-triggered reminder as shown, create/read its channel and
# verify that Android has not disabled it. This prevents a false "shown" ACK.
replace_once(
    loc,
    '''        val now = System.currentTimeMillis()\n        publishLocalStatus("location_reminder_received_$transport")\n        when {\n            expiresAt > 0L && now > expiresAt -> publishLocationReminderAck(ts, "expired")\n            isLocationEnabled() -> publishLocationReminderAck(ts, "already_on")\n            !hasNotificationPermission() -> publishLocationReminderAck(ts, "notification_permission_missing")\n            else -> {''',
    '''        val now = System.currentTimeMillis()\n        publishLocalStatus("location_reminder_received_$transport")\n        if (hasNotificationPermission()) ensureLocationReminderChannel()\n        when {\n            expiresAt > 0L && now > expiresAt -> publishLocationReminderAck(ts, "expired")\n            isLocationEnabled() -> publishLocationReminderAck(ts, "already_on")\n            !hasNotificationPermission() -> publishLocationReminderAck(ts, "notification_permission_missing")\n            !isNotificationChannelEnabled(REMINDER_CHANNEL_ID) -> publishLocationReminderAck(ts, "notification_channel_disabled")\n            else -> {'''
)

# Channel creation is centralized so it can be checked before attempting to post.
replace_once(
    loc,
    '''        val nm = getSystemService(NotificationManager::class.java)\n        if (Build.VERSION.SDK_INT >= 26) {\n            nm.createNotificationChannel(\n                NotificationChannel(channelId, "Lời nhắc từ gia đình", NotificationManager.IMPORTANCE_HIGH).apply {\n                    description = "Thông báo được gửi khi gia đình chủ động nhắc bật Vị trí"\n                }\n            )\n        }\n\n        val openApp = Intent(this, MainActivity::class.java).apply {''',
    '''        val nm = getSystemService(NotificationManager::class.java)\n        ensureLocationReminderChannel()\n\n        val openApp = Intent(this, MainActivity::class.java).apply {'''
)

# Respect a user-disabled protection channel: do not keep making silent re-post
# attempts every eight hours when Android will suppress them anyway.
replace_once(
    loc,
    '''        if (!hasNotificationPermission()) return\n        getSystemService(NotificationManager::class.java).notify(TRACKING_NOTIFICATION_ID, trackingNotification())''',
    '''        if (!isNotificationChannelEnabled(PROTECTION_CHANNEL_ID)) return\n        getSystemService(NotificationManager::class.java).notify(TRACKING_NOTIFICATION_ID, trackingNotification())'''
)

# When Location returns ON, restore the quiet protection notification only if the
# user has not disabled that notification channel in Android settings.
replace_once(
    loc,
    '''            if (fine && locationOn && locationWasOff) {\n                getSystemService(NotificationManager::class.java).notify(TRACKING_NOTIFICATION_ID, trackingNotification())\n                getSharedPreferences("tracking_diag", MODE_PRIVATE).edit()''',
    '''            if (fine && locationOn && locationWasOff && isNotificationChannelEnabled(PROTECTION_CHANNEL_ID)) {\n                getSystemService(NotificationManager::class.java).notify(TRACKING_NOTIFICATION_ID, trackingNotification())\n                getSharedPreferences("tracking_diag", MODE_PRIVATE).edit()'''
)

# Global notification state must include both POST_NOTIFICATIONS and Android's
# app-level notification switch. Channel state is checked separately on API 26+.
replace_once(
    loc,
    '''    private fun hasNotificationPermission(): Boolean =\n        Build.VERSION.SDK_INT < 33 || ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED\n\n    private fun isLocationEnabled(): Boolean = try {''',
    '''    private fun hasNotificationPermission(): Boolean {\n        if (Build.VERSION.SDK_INT >= 33 && ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false\n        return try {\n            val nm = getSystemService(NotificationManager::class.java)\n            Build.VERSION.SDK_INT < 24 || nm.areNotificationsEnabled()\n        } catch (_: Exception) {\n            true\n        }\n    }\n\n    private fun isNotificationChannelEnabled(channelId: String): Boolean {\n        if (!hasNotificationPermission()) return false\n        if (Build.VERSION.SDK_INT < 26) return true\n        return try {\n            val channel = getSystemService(NotificationManager::class.java).getNotificationChannel(channelId)\n            channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE\n        } catch (_: Exception) {\n            true\n        }\n    }\n\n    private fun ensureLocationReminderChannel() {\n        if (Build.VERSION.SDK_INT < 26) return\n        getSystemService(NotificationManager::class.java).createNotificationChannel(\n            NotificationChannel(REMINDER_CHANNEL_ID, "Lời nhắc từ gia đình", NotificationManager.IMPORTANCE_HIGH).apply {\n                description = "Thông báo được gửi khi gia đình chủ động nhắc bật Vị trí"\n            }\n        )\n    }\n\n    private fun isLocationEnabled(): Boolean = try {'''
)

# Add stable channel IDs beside the notification IDs.
replace_once(
    loc,
    '''        private const val TRACKING_NOTIFICATION_ID = 42\n        private const val LOCATION_REMINDER_NOTIFICATION_ID = 77''',
    '''        private const val TRACKING_NOTIFICATION_ID = 42\n        private const val LOCATION_REMINDER_NOTIFICATION_ID = 77\n        private const val PROTECTION_CHANNEL_ID = "protection"\n        private const val REMINDER_CHANNEL_ID = "family_location_reminder"'''
)

# Parent must display the true reason when Android has disabled only the reminder
# channel, instead of telling the user that the reminder was shown.
replace_once(
    parent,
    '''            "notification_permission_missing" -> "Máy Con chưa cấp quyền thông báo nên chưa thể hiện lời nhắc."\n            "expired" -> "Lời nhắc đã hết hạn trước khi Máy Con nhận."''',
    '''            "notification_permission_missing" -> "Máy Con đang tắt thông báo của ứng dụng nên chưa thể hiện lời nhắc."\n            "notification_channel_disabled" -> "Máy Con đã tắt loại thông báo nhắc bật Vị trí trong cài đặt Android."\n            "expired" -> "Lời nhắc đã hết hạn trước khi Máy Con nhận."'''
)

# Build-time guard: fail rather than ship a partially patched release.
checks = {
    loc: [
        'notification_channel_disabled',
        'isNotificationChannelEnabled(REMINDER_CHANNEL_ID)',
        'isNotificationChannelEnabled(PROTECTION_CHANNEL_ID)',
        'nm.areNotificationsEnabled()',
        'PROTECTION_CHANNEL_ID = "protection"',
        'REMINDER_CHANNEL_ID = "family_location_reminder"',
        'setContentIntent(locationSettingsPi)',
        'TRACKING_NOTIFICATION_RESHOW_MS = 8 * 60 * 60_000L',
    ],
    parent: [
        'Máy Con đã tắt loại thông báo nhắc bật Vị trí trong cài đặt Android.',
        'LOCATION_REMINDER_DELAY_MS = 6 * 60 * 60_000L',
    ],
}
for path, needles in checks.items():
    text = Path(path).read_text(encoding='utf-8')
    for needle in needles:
        if needle not in text:
            raise SystemExit(f'v2.2.3 validation missing {needle!r} in {path}')

print('v2.2.3 notification channel patch applied')
