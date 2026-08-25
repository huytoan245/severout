from pathlib import Path
import re


def read(path):
    p = Path(path)
    if not p.exists():
        raise SystemExit(f"missing file: {p}")
    return p, p.read_text(encoding="utf-8")


def replace_once(path, old, new):
    p, text = read(path)
    if old not in text:
        raise SystemExit(f"anchor not found in {path}: {old[:180]!r}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


loc = "appsrc/child-app/src/main/java/com/family/child/LocationService.kt"
child_main = "appsrc/child-app/src/main/java/com/family/child/MainActivity.kt"
child_manifest = "appsrc/child-app/src/main/AndroidManifest.xml"
watchdog = "appsrc/child-app/src/main/java/com/family/child/ServiceWatchdogWorker.kt"

# -----------------------------------------------------------------------------
# Manifest hardening: the location foreground service must not be tied to the
# launcher task lifecycle. This does not bypass Force Stop or Restricted mode.
# -----------------------------------------------------------------------------
mp, manifest = read(child_manifest)
service_pattern = re.compile(r'(<service\b[^>]*android:name="(?:\.LocationService|com\.family\.child\.LocationService)"[^>]*)(/?>)', re.S)
m = service_pattern.search(manifest)
if not m:
    raise SystemExit("LocationService manifest declaration not found")
service_tag = m.group(0)
if 'android:stopWithTask=' not in service_tag:
    new_tag = service_tag[:-2] + ' android:stopWithTask="false" />' if service_tag.endswith('/>') else service_tag[:-1] + ' android:stopWithTask="false">'
    manifest = manifest[:m.start()] + new_tag + manifest[m.end():]
mp.write_text(manifest, encoding="utf-8")

# -----------------------------------------------------------------------------
# CHILD ACTIVITY: include the App Standby Restricted bucket in the protection
# check and do not mistake "settings screen opened" for "Never sleeping done".
# Also capture the most recent Android process-exit reason after a restart.
# -----------------------------------------------------------------------------
replace_once(
    child_main,
    "import android.app.ActivityManager\nimport android.content.Intent",
    "import android.app.ActivityManager\nimport android.app.ApplicationExitInfo\nimport android.app.usage.UsageStatsManager\nimport android.content.Intent"
)

replace_once(
    child_main,
    '''    override fun onResume() {\n        super.onResume()\n        evaluateSetup()\n    }''',
    '''    override fun onResume() {\n        super.onResume()\n        recordLastProcessExitReason()\n        evaluateSetup()\n    }'''
)

replace_once(
    child_main,
    '''        val activity = getSystemService(ActivityManager::class.java)\n        val backgroundRestricted = Build.VERSION.SDK_INT >= 28 && activity.isBackgroundRestricted\n        backgroundProtectionNeeded = !batteryExempt || backgroundRestricted\n        samsungNeverSleepingSetupNeeded = Build.MANUFACTURER.equals("samsung", ignoreCase = true) &&\n            !getSharedPreferences("tracking_diag", MODE_PRIVATE).getBoolean("samsung_never_sleeping_setup_opened", false)''',
    '''        val activity = getSystemService(ActivityManager::class.java)\n        val backgroundRestricted = Build.VERSION.SDK_INT >= 28 && activity.isBackgroundRestricted\n        val standbyRestricted = try {\n            Build.VERSION.SDK_INT >= 28 &&\n                getSystemService(UsageStatsManager::class.java).appStandbyBucket == UsageStatsManager.STANDBY_BUCKET_RESTRICTED\n        } catch (_: Exception) { false }\n        val trackingPrefs = getSharedPreferences("tracking_diag", MODE_PRIVATE)\n        if (standbyRestricted) {\n            trackingPrefs.edit().putBoolean("samsung_never_sleeping_confirmed", false).apply()\n        }\n        backgroundProtectionNeeded = !batteryExempt || backgroundRestricted || standbyRestricted\n        samsungNeverSleepingSetupNeeded = Build.MANUFACTURER.equals("samsung", ignoreCase = true) &&\n            !trackingPrefs.getBoolean("samsung_never_sleeping_confirmed", false)'''
)

replace_once(
    child_main,
    '''                onBackgroundProtection = { requestAlwaysActiveMode() },\n                onSamsungNeverSleeping = { openSamsungNeverSleeping() },\n                onLocationSettings = { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }''',
    '''                onBackgroundProtection = { requestAlwaysActiveMode() },\n                onSamsungNeverSleeping = { openSamsungNeverSleeping() },\n                onSamsungNeverSleepingConfirmed = { confirmSamsungNeverSleeping() },\n                onLocationSettings = { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }'''
)

replace_once(
    child_main,
    '''    private fun openSamsungNeverSleeping() {\n        getSharedPreferences("tracking_diag", MODE_PRIVATE).edit()\n            .putBoolean("samsung_never_sleeping_setup_opened", true)\n            .apply()\n        samsungNeverSleepingSetupNeeded = false\n        val samsungIntent = Intent("com.samsung.android.sm.ACTION_OPEN_CHECKABLE_LISTACTIVITY")''',
    '''    private fun openSamsungNeverSleeping() {\n        getSharedPreferences("tracking_diag", MODE_PRIVATE).edit()\n            .putLong("samsung_never_sleeping_setup_opened_at", System.currentTimeMillis())\n            .apply()\n        val samsungIntent = Intent("com.samsung.android.sm.ACTION_OPEN_CHECKABLE_LISTACTIVITY")'''
)

replace_once(
    child_main,
    '''    private fun isLocationEnabled(): Boolean = try {''',
    '''    private fun confirmSamsungNeverSleeping() {\n        getSharedPreferences("tracking_diag", MODE_PRIVATE).edit()\n            .putBoolean("samsung_never_sleeping_confirmed", true)\n            .putLong("samsung_never_sleeping_confirmed_at", System.currentTimeMillis())\n            .apply()\n        samsungNeverSleepingSetupNeeded = false\n        evaluateSetup()\n    }\n\n    private fun recordLastProcessExitReason() {\n        if (Build.VERSION.SDK_INT < 30) return\n        try {\n            val exits = getSystemService(ActivityManager::class.java)\n                .getHistoricalProcessExitReasons(packageName, 0, 1)\n            val last = exits.firstOrNull() ?: return\n            getSharedPreferences("tracking_diag", MODE_PRIVATE).edit()\n                .putInt("last_process_exit_reason", last.reason)\n                .putLong("last_process_exit_at", last.timestamp)\n                .putString("last_process_exit_description", last.description?.take(160) ?: "")\n                .apply()\n        } catch (_: Exception) { }\n    }\n\n    private fun isLocationEnabled(): Boolean = try {'''
)

replace_once(
    child_main,
    '''    onSamsungNeverSleeping: () -> Unit,\n    onLocationSettings: () -> Unit''',
    '''    onSamsungNeverSleeping: () -> Unit,\n    onSamsungNeverSleepingConfirmed: () -> Unit,\n    onLocationSettings: () -> Unit'''
)

replace_once(
    child_main,
    '''                                TextButton(onClick = onSamsungNeverSleeping) { Text("MỞ DANH SÁCH KHÔNG BAO GIỜ TỰ NGHỈ") }\n                            }''',
    '''                                TextButton(onClick = onSamsungNeverSleeping) { Text("MỞ DANH SÁCH KHÔNG BAO GIỜ TỰ NGHỈ") }\n                                TextButton(onClick = onSamsungNeverSleepingConfirmed) { Text("ĐÃ THÊM APP VÀO DANH SÁCH") }\n                            }'''
)

# -----------------------------------------------------------------------------
# LOCATION SERVICE: request immediate secondary recovery when Android removes
# the task/service through normal lifecycle callbacks. A real process kill may
# skip onDestroy; START_STICKY + periodic watchdog remain the other layers.
# -----------------------------------------------------------------------------
replace_once(
    loc,
    '''    override fun onBind(intent: Intent?): IBinder? = null\n\n    private fun trackingNotification(): Notification {''',
    '''    override fun onBind(intent: Intent?): IBinder? = null\n\n    override fun onTaskRemoved(rootIntent: Intent?) {\n        getSharedPreferences("tracking_diag", MODE_PRIVATE).edit()\n            .putLong("service_task_removed_at", System.currentTimeMillis())\n            .apply()\n        ServiceWatchdogWorker.requestImmediateRecovery(this, "task_removed")\n        super.onTaskRemoved(rootIntent)\n    }\n\n    override fun onTrimMemory(level: Int) {\n        getSharedPreferences("tracking_diag", MODE_PRIVATE).edit()\n            .putInt("last_trim_memory_level", level)\n            .putLong("last_trim_memory_at", System.currentTimeMillis())\n            .apply()\n        super.onTrimMemory(level)\n    }\n\n    private fun trackingNotification(): Notification {'''
)

# Add one-shot recovery to the existing v2.2.6 onDestroy without deleting any
# cleanup that protects GPS/network resources.
p, loc_text = read(loc)
on_destroy = re.search(r'override fun onDestroy\(\) \{(?P<body>.*?)\n    \}', loc_text, re.S)
if not on_destroy:
    raise SystemExit("LocationService onDestroy not found after v2.2.6")
body = on_destroy.group('body')
if 'requestImmediateRecovery(this, "service_destroyed")' not in body:
    injected = body + '''\n        getSharedPreferences("tracking_diag", MODE_PRIVATE).edit()\n            .putLong("service_destroyed_at", System.currentTimeMillis())\n            .apply()\n        ServiceWatchdogWorker.requestImmediateRecovery(this, "service_destroyed")'''
    loc_text = loc_text[:on_destroy.start('body')] + injected + loc_text[on_destroy.end('body'):]
p.write_text(loc_text, encoding="utf-8")

# Extend cloud diagnostics with system-survival evidence.
replace_once(
    loc,
    '''            "watchdogResult" to getSharedPreferences("tracking_diag", MODE_PRIVATE).getString(ServiceWatchdogWorker.KEY_WATCHDOG_RESULT, "unknown")\n        )''',
    '''            "watchdogResult" to getSharedPreferences("tracking_diag", MODE_PRIVATE).getString(ServiceWatchdogWorker.KEY_WATCHDOG_RESULT, "unknown"),\n            "watchdogTrigger" to getSharedPreferences("tracking_diag", MODE_PRIVATE).getString(ServiceWatchdogWorker.KEY_WATCHDOG_TRIGGER, "unknown"),\n            "lastProcessExitReason" to getSharedPreferences("tracking_diag", MODE_PRIVATE).getInt("last_process_exit_reason", 0),\n            "lastProcessExitAt" to getSharedPreferences("tracking_diag", MODE_PRIVATE).getLong("last_process_exit_at", 0L),\n            "lastTrimMemoryLevel" to getSharedPreferences("tracking_diag", MODE_PRIVATE).getInt("last_trim_memory_level", 0),\n            "serviceTaskRemovedAt" to getSharedPreferences("tracking_diag", MODE_PRIVATE).getLong("service_task_removed_at", 0L),\n            "serviceDestroyedAt" to getSharedPreferences("tracking_diag", MODE_PRIVATE).getLong("service_destroyed_at", 0L)\n        )'''
)

checks = {
    child_manifest: [
        'android:stopWithTask="false"',
        'android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS',
    ],
    watchdog: [
        'OneTimeWorkRequestBuilder<ServiceWatchdogWorker>()',
        'setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)',
        'requestImmediateRecovery',
        'KEY_WATCHDOG_TRIGGER',
    ],
    child_main: [
        'UsageStatsManager.STANDBY_BUCKET_RESTRICTED',
        'samsung_never_sleeping_confirmed',
        'ĐÃ THÊM APP VÀO DANH SÁCH',
        'getHistoricalProcessExitReasons',
    ],
    loc: [
        'override fun onTaskRemoved(rootIntent: Intent?)',
        'requestImmediateRecovery(this, "task_removed")',
        'requestImmediateRecovery(this, "service_destroyed")',
        'lastProcessExitReason',
        'watchdogTrigger',
        'return START_STICKY',
    ],
}
for path, needles in checks.items():
    text = Path(path).read_text(encoding="utf-8")
    for needle in needles:
        if needle not in text:
            raise SystemExit(f"v2.2.7 validation missing {needle!r} in {path}")

print("v2.2.7 always-active hardening patch applied")
