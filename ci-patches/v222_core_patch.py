from pathlib import Path
import json

ROOT = Path('appsrc')


def read(rel):
    p = ROOT / rel
    if not p.exists():
        raise SystemExit(f'missing file: {p}')
    return p, p.read_text(encoding='utf-8')


def replace_once(rel, old, new):
    p, s = read(rel)
    if old not in s:
        raise SystemExit(f'anchor not found in {rel}: {old[:120]!r}')
    p.write_text(s.replace(old, new, 1), encoding='utf-8')


# Child UI text: keep the permanent protection sentence exactly as requested,
# but change rotating content to online-scam prevention guidance.
replace_once(
    'child-app/src/main/java/com/family/child/MainActivity.kt',
    '"Chạm màn hình để xem lời nhắn khác · ${WisdomStore.count()} câu"',
    '"Chạm màn hình để xem cảnh báo khác · ${WisdomStore.count()} lời khuyên"'
)
replace_once(
    'child-app/src/main/java/com/family/child/MainActivity.kt',
    '"Hãy bật Vị trí để bảo vệ điện thoại an toàn."',
    '"Bật vị trí để được bảo vệ an toàn"'
)

# Child service: make the parent-requested location reminder tappable over the
# whole notification, record continuous Location-off duration, and allow the
# quiet foreground notification to be dismissed where Android permits. A user
# dismissal is respected for 8 hours before a silent re-post attempt.
loc_rel = 'child-app/src/main/java/com/family/child/LocationService.kt'
replace_once(
    loc_rel,
    '''    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {\n        if (intent?.getBooleanExtra("immediate", false) == true) {''',
    '''    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {\n        if (intent?.action == ACTION_TRACKING_NOTIFICATION_DISMISSED) {\n            getSharedPreferences("tracking_diag", MODE_PRIVATE).edit()\n                .putLong(KEY_TRACKING_NOTIFICATION_DISMISSED_AT, System.currentTimeMillis())\n                .apply()\n            publishLocalStatus("tracking_notification_dismissed")\n            return START_STICKY\n        }\n        if (intent?.getBooleanExtra("immediate", false) == true) {'''
)
replace_once(
    loc_rel,
    '''        val pi = PendingIntent.getActivity(\n            this,\n            0,\n            Intent(this, MainActivity::class.java),\n            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT\n        )\n        return NotificationCompat.Builder(this, id)''',
    '''        val pi = PendingIntent.getActivity(\n            this,\n            0,\n            Intent(this, MainActivity::class.java),\n            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT\n        )\n        val deletePi = PendingIntent.getService(\n            this,\n            1,\n            Intent(this, LocationService::class.java).setAction(ACTION_TRACKING_NOTIFICATION_DISMISSED),\n            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT\n        )\n        return NotificationCompat.Builder(this, id)'''
)
replace_once(
    loc_rel,
    '''            .setContentIntent(pi)\n            .setOngoing(true)\n            .setSilent(true)\n            .setPriority(NotificationCompat.PRIORITY_LOW)''',
    '''            .setContentIntent(pi)\n            .setDeleteIntent(deletePi)\n            .setOngoing(false)\n            .setOnlyAlertOnce(true)\n            .setSilent(true)\n            .setPriority(NotificationCompat.PRIORITY_LOW)'''
)
replace_once(
    loc_rel,
    '''            .setContentTitle("Lời nhắc từ gia đình")\n            .setContentText("Hãy bật Vị trí để bảo vệ điện thoại an toàn.")\n            .setStyle(NotificationCompat.BigTextStyle().bigText("Hãy bật Vị trí để bảo vệ điện thoại an toàn. Nhấn BẬT VỊ TRÍ để mở cài đặt hệ thống."))\n            .setContentIntent(openAppPi)''',
    '''            .setContentTitle("Bật vị trí để được bảo vệ an toàn")\n            .setContentText("Chạm để mở cài đặt Vị trí.")\n            .setStyle(NotificationCompat.BigTextStyle().bigText("Bật vị trí để được bảo vệ an toàn. Chạm thông báo để mở cài đặt Vị trí."))\n            .setContentIntent(locationSettingsPi)'''
)
replace_once(
    loc_rel,
    '''    private fun publishHeartbeat() {\n        if (FirebaseAuth.getInstance().currentUser == null) return\n        val now = System.currentTimeMillis()''',
    '''    private fun publishHeartbeat() {\n        val now = System.currentTimeMillis()\n        maintainTrackingNotification(now)\n        if (FirebaseAuth.getInstance().currentUser == null) return'''
)
replace_once(
    loc_rel,
    '''    private fun diagnosticFields(now: Long): MutableMap<String, Any?> {\n        val network = networkState()\n        return mutableMapOf(\n            "diagnosticAt" to now,\n            "locationEnabled" to isLocationEnabled(),''',
    '''    private fun diagnosticFields(now: Long): MutableMap<String, Any?> {\n        val network = networkState()\n        val locationEnabled = isLocationEnabled()\n        val locationOffSince = updateLocationOffSince(now, locationEnabled)\n        return mutableMapOf(\n            "diagnosticAt" to now,\n            "locationEnabled" to locationEnabled,\n            "locationOffSince" to locationOffSince,'''
)
replace_once(
    loc_rel,
    '''    private fun networkState(): Pair<String, Boolean> {''',
    '''    private fun updateLocationOffSince(now: Long, locationEnabled: Boolean): Long {\n        val prefs = getSharedPreferences("tracking_diag", MODE_PRIVATE)\n        val existing = prefs.getLong(KEY_LOCATION_OFF_SINCE, 0L)\n        if (locationEnabled) {\n            if (existing != 0L) prefs.edit().remove(KEY_LOCATION_OFF_SINCE).apply()\n            return 0L\n        }\n        if (existing in 1..now) return existing\n        prefs.edit().putLong(KEY_LOCATION_OFF_SINCE, now).apply()\n        return now\n    }\n\n    private fun maintainTrackingNotification(now: Long) {\n        val prefs = getSharedPreferences("tracking_diag", MODE_PRIVATE)\n        val dismissedAt = prefs.getLong(KEY_TRACKING_NOTIFICATION_DISMISSED_AT, 0L)\n        if (dismissedAt <= 0L || now - dismissedAt < TRACKING_NOTIFICATION_RESHOW_MS) return\n        if (!hasNotificationPermission()) return\n        getSystemService(NotificationManager::class.java).notify(TRACKING_NOTIFICATION_ID, trackingNotification())\n        prefs.edit().remove(KEY_TRACKING_NOTIFICATION_DISMISSED_AT).apply()\n        publishLocalStatus("tracking_notification_restored")\n    }\n\n    private fun networkState(): Pair<String, Boolean> {'''
)
replace_once(
    loc_rel,
    '''        private const val FIREBASE_HEALTH_THROTTLE_MS = 60_000L\n        private const val MAX_ACCURACY_M = 150f''',
    '''        private const val FIREBASE_HEALTH_THROTTLE_MS = 60_000L\n        private const val TRACKING_NOTIFICATION_RESHOW_MS = 8 * 60 * 60_000L\n        private const val ACTION_TRACKING_NOTIFICATION_DISMISSED = "com.family.child.TRACKING_NOTIFICATION_DISMISSED"\n        private const val KEY_TRACKING_NOTIFICATION_DISMISSED_AT = "tracking_notification_dismissed_at"\n        private const val KEY_LOCATION_OFF_SINCE = "location_off_since"\n        private const val MAX_ACCURACY_M = 150f'''
)

# REST fallback schema must carry the same Location-off start time so Parent can
# calculate the six-hour threshold even when Firestore realtime is unavailable.
bridge_rel = 'parent-app/src/main/java/com/family/parent/ParentHttpsBridge.kt'
replace_once(
    bridge_rel,
    '''        val locationEnabled: Boolean? = null,\n        val fineLocationGranted: Boolean? = null,''',
    '''        val locationEnabled: Boolean? = null,\n        val locationOffSince: Long = 0L,\n        val fineLocationGranted: Boolean? = null,'''
)
replace_once(
    bridge_rel,
    '''                                locationEnabled = bool(fields, "locationEnabled"),\n                                fineLocationGranted = bool(fields, "fineLocationGranted"),''',
    '''                                locationEnabled = bool(fields, "locationEnabled"),\n                                locationOffSince = long(fields, "locationOffSince"),\n                                fineLocationGranted = bool(fields, "fineLocationGranted"),'''
)

# Parent UI: Location-off is reported immediately, but the action button only
# appears after six continuous hours. Parent never sends the reminder automatically.
parent_rel = 'parent-app/src/main/java/com/family/parent/MainActivity.kt'
replace_once(
    parent_rel,
    '''private const val REMINDER_COOLDOWN_MS = 45_000L''',
    '''private const val REMINDER_COOLDOWN_MS = 45_000L\nprivate const val LOCATION_REMINDER_DELAY_MS = 6 * 60 * 60_000L'''
)
replace_once(
    parent_rel,
    '''    var locationEnabled by remember { mutableStateOf<Boolean?>(null) }\n    var fineLocationGranted by remember { mutableStateOf<Boolean?>(null) }''',
    '''    var locationEnabled by remember { mutableStateOf<Boolean?>(null) }\n    var locationOffSince by remember { mutableLongStateOf(0L) }\n    var fineLocationGranted by remember { mutableStateOf<Boolean?>(null) }'''
)
replace_once(
    parent_rel,
    '''        if (s.locationEnabled != null) locationEnabled = s.locationEnabled\n        if (s.fineLocationGranted != null) fineLocationGranted = s.fineLocationGranted''',
    '''        if (s.locationEnabled != null) locationEnabled = s.locationEnabled\n        locationOffSince = s.locationOffSince\n        if (s.fineLocationGranted != null) fineLocationGranted = s.fineLocationGranted'''
)
replace_once(
    parent_rel,
    '''                locationEnabled = d.getBoolean("locationEnabled")\n                fineLocationGranted = d.getBoolean("fineLocationGranted")''',
    '''                locationEnabled = d.getBoolean("locationEnabled")\n                locationOffSince = d.getLong("locationOffSince") ?: locationOffSince\n                fineLocationGranted = d.getBoolean("fineLocationGranted")'''
)
replace_once(
    parent_rel,
    '''    val locationStateFresh = diagnosticAt > 0L && now - diagnosticAt <= DIAGNOSTIC_FRESH_MS\n    val confirmedLocationOff = locationStateFresh && locationEnabled == false\n    val firebaseChildRecent''',
    '''    val locationStateFresh = diagnosticAt > 0L && now - diagnosticAt <= DIAGNOSTIC_FRESH_MS\n    val confirmedLocationOff = locationStateFresh && locationEnabled == false\n    val locationOffDurationMs = if (confirmedLocationOff && locationOffSince > 0L) (now - locationOffSince).coerceAtLeast(0L) else 0L\n    val locationReminderEligible = confirmedLocationOff && locationOffSince > 0L && locationOffDurationMs >= LOCATION_REMINDER_DELAY_MS\n    val firebaseChildRecent'''
)
replace_once(
    parent_rel,
    '''                confirmedLocationOff = confirmedLocationOff,\n                locationDiagnosticAt = diagnosticAt,''',
    '''                confirmedLocationOff = confirmedLocationOff,\n                locationReminderEligible = locationReminderEligible,\n                locationOffDurationMs = locationOffDurationMs,\n                locationDiagnosticAt = diagnosticAt,'''
)
replace_once(
    parent_rel,
    '''                    if (!confirmedLocationOff || reminderRequestId > 0L || now - lastReminderSentAt < REMINDER_COOLDOWN_MS) return@HomeScreen''',
    '''                    if (!locationReminderEligible || reminderRequestId > 0L || now - lastReminderSentAt < REMINDER_COOLDOWN_MS) return@HomeScreen'''
)
replace_once(
    parent_rel,
    '''    confirmedLocationOff: Boolean,\n    locationDiagnosticAt: Long,''',
    '''    confirmedLocationOff: Boolean,\n    locationReminderEligible: Boolean,\n    locationOffDurationMs: Long,\n    locationDiagnosticAt: Long,'''
)
old_card = '''                        Text("Vị trí trên Máy Con đang tắt", color = Color(0xFFFFC857), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)\n                        Spacer(Modifier.height(4.dp))\n                        Text("Xác nhận lúc ${fmt(locationDiagnosticAt)}. Lời nhắc chỉ được gửi khi bạn bấm nút bên dưới.", color = Color(0xFFD8C79C), style = MaterialTheme.typography.bodySmall)\n                        Spacer(Modifier.height(12.dp))\n                        Button(\n                            onClick = onReminder,\n                            enabled = !reminderBusy && reminderCooldown <= 0L,\n                            modifier = Modifier.fillMaxWidth()\n                        ) {\n                            Text(\n                                when {\n                                    reminderBusy -> "ĐANG GỬI LỜI NHẮC..."\n                                    reminderCooldown > 0L -> "CÓ THỂ NHẮC LẠI SAU ${maxOf(1L, reminderCooldown / 1000L)}S"\n                                    else -> "NHẮC BẬT VỊ TRÍ"\n                                }\n                            )\n                        }'''
new_card = '''                        Text("Vị trí trên Máy Con đang tắt", color = Color(0xFFFFC857), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)\n                        Spacer(Modifier.height(4.dp))\n                        Text("Xác nhận lúc ${fmt(locationDiagnosticAt)}.", color = Color(0xFFD8C79C), style = MaterialTheme.typography.bodySmall)\n                        Spacer(Modifier.height(8.dp))\n                        if (locationReminderEligible) {\n                            Text("Vị trí đã tắt liên tục đủ 6 giờ. Lời nhắc chỉ được gửi khi bạn chủ động bấm nút.", color = Color(0xFFD8C79C), style = MaterialTheme.typography.bodySmall)\n                            Spacer(Modifier.height(12.dp))\n                            Button(\n                                onClick = onReminder,\n                                enabled = !reminderBusy && reminderCooldown <= 0L,\n                                modifier = Modifier.fillMaxWidth()\n                            ) {\n                                Text(\n                                    when {\n                                        reminderBusy -> "ĐANG GỬI LỜI NHẮC..."\n                                        reminderCooldown > 0L -> "CÓ THỂ NHẮC LẠI SAU ${maxOf(1L, reminderCooldown / 1000L)}S"\n                                        else -> "NHẮC BẬT VỊ TRÍ"\n                                    }\n                                )\n                            }\n                        } else {\n                            val remainingMs = (LOCATION_REMINDER_DELAY_MS - locationOffDurationMs).coerceAtLeast(0L)\n                            val remainingHours = maxOf(1L, (remainingMs + 60 * 60_000L - 1L) / (60 * 60_000L))\n                            Text("Nút NHẮC BẬT VỊ TRÍ sẽ tự xuất hiện nếu Vị trí vẫn tắt liên tục. Còn khoảng $remainingHours giờ.", color = Color(0xFFBFCED6), style = MaterialTheme.typography.bodySmall)\n                        }'''
replace_once(parent_rel, old_card, new_card)

# Static validations guard against accidentally producing an APK with only part
# of the requested behavior.
checks = {
    'child-app/src/main/java/com/family/child/MainActivity.kt': [
        'Bật vị trí để được bảo vệ an toàn',
        'Điện thoại của bạn đang được bảo vệ an toàn.',
        'Không phát hiện mối đe dọa, lừa đảo.',
        '220 lời khuyên',
    ],
    'child-app/src/main/java/com/family/child/LocationService.kt': [
        'setContentIntent(locationSettingsPi)',
        'TRACKING_NOTIFICATION_RESHOW_MS = 8 * 60 * 60_000L',
        '"locationOffSince" to locationOffSince',
        'setDeleteIntent(deletePi)',
    ],
    'parent-app/src/main/java/com/family/parent/MainActivity.kt': [
        'LOCATION_REMINDER_DELAY_MS = 6 * 60 * 60_000L',
        'locationReminderEligible',
        'NHẮC BẬT VỊ TRÍ',
    ],
    'parent-app/src/main/java/com/family/parent/ParentHttpsBridge.kt': [
        'val locationOffSince: Long = 0L',
        'locationOffSince = long(fields, "locationOffSince")',
    ],
}
for rel, needles in checks.items():
    text = (ROOT / rel).read_text(encoding='utf-8')
    for needle in needles:
        if needle not in text:
            raise SystemExit(f'v2.2.2 validation missing {needle!r} in {rel}')

print('v2.2.2 patch applied: 220 safety tips, 6h Parent reminder gate, tappable Child reminder, 8h quiet notification re-show')
