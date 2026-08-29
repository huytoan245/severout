from pathlib import Path


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
boot = "appsrc/child-app/src/main/java/com/family/child/BootReceiver.kt"
parent = "appsrc/parent-app/src/main/java/com/family/parent/MainActivity.kt"
parent_https = "appsrc/parent-app/src/main/java/com/family/parent/ParentHttpsBridge.kt"
child_manifest = "appsrc/child-app/src/main/AndroidManifest.xml"
watchdog_src = Path("ci-patches/ServiceWatchdogWorker.kt")
watchdog_dst = Path("appsrc/child-app/src/main/java/com/family/child/ServiceWatchdogWorker.kt")

if not watchdog_src.exists():
    raise SystemExit("missing ci-patches/ServiceWatchdogWorker.kt")
watchdog_dst.write_text(watchdog_src.read_text(encoding="utf-8"), encoding="utf-8")

# -----------------------------------------------------------------------------
# Manifest: direct, user-visible battery-optimization exemption request.
# -----------------------------------------------------------------------------
mp, manifest = read(child_manifest)
if "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" not in manifest:
    pos = manifest.find(">")
    if pos < 0:
        raise SystemExit("child manifest opening tag not found")
    manifest = manifest[:pos + 1] + '\n    <uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />' + manifest[pos + 1:]
    mp.write_text(manifest, encoding="utf-8")

# -----------------------------------------------------------------------------
# CHILD ACTIVITY: guide the user through the two controls that an app cannot
# silently override: Android battery restriction and Samsung Never sleeping.
# Samsung's deeplink is from Samsung's official Application Management guide.
# -----------------------------------------------------------------------------
replace_once(
    child_main,
    "import android.Manifest\nimport android.content.Intent",
    "import android.Manifest\nimport android.app.ActivityManager\nimport android.content.Intent"
)
replace_once(
    child_main,
    "import android.os.Build\nimport android.os.Bundle",
    "import android.os.Build\nimport android.os.Bundle\nimport android.os.PowerManager"
)
replace_once(
    child_main,
    '''    private var locationReminderActive by mutableStateOf(false)''',
    '''    private var locationReminderActive by mutableStateOf(false)\n    private var backgroundProtectionNeeded by mutableStateOf(false)\n    private var samsungNeverSleepingSetupNeeded by mutableStateOf(false)'''
)
replace_once(
    child_main,
    '''                showLocationReminder = locationReminderActive && !isLocationEnabled(),\n                onPermissionSettings = { openAppSettings() },\n                onLocationSettings = { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }''',
    '''                showLocationReminder = locationReminderActive && !isLocationEnabled(),\n                backgroundProtectionNeeded = backgroundProtectionNeeded,\n                samsungNeverSleepingSetupNeeded = samsungNeverSleepingSetupNeeded,\n                onPermissionSettings = { openAppSettings() },\n                onBackgroundProtection = { requestAlwaysActiveMode() },\n                onSamsungNeverSleeping = { openSamsungNeverSleeping() },\n                onLocationSettings = { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }'''
)
replace_once(
    child_main,
    '''        if (Build.VERSION.SDK_INT >= 30 && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {\n            setupMessage = "Để tự khôi phục tốt hơn sau khi khởi động lại máy, hãy cho phép vị trí 'Mọi lúc'."\n            canOpenPermissionSettings = true\n        } else {\n            setupMessage = null\n        }''',
    '''        if (Build.VERSION.SDK_INT >= 30 && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {\n            setupMessage = "Để tự khôi phục tốt hơn sau khi khởi động lại máy, hãy cho phép vị trí 'Mọi lúc'."\n            canOpenPermissionSettings = true\n        } else {\n            setupMessage = null\n        }\n\n        ServiceWatchdogWorker.schedule(this)\n        val power = getSystemService(PowerManager::class.java)\n        val batteryExempt = Build.VERSION.SDK_INT < 23 || power.isIgnoringBatteryOptimizations(packageName)\n        val activity = getSystemService(ActivityManager::class.java)\n        val backgroundRestricted = Build.VERSION.SDK_INT >= 28 && activity.isBackgroundRestricted\n        backgroundProtectionNeeded = !batteryExempt || backgroundRestricted\n        samsungNeverSleepingSetupNeeded = Build.MANUFACTURER.equals("samsung", ignoreCase = true) &&\n            !getSharedPreferences("tracking_diag", MODE_PRIVATE).getBoolean("samsung_never_sleeping_setup_opened", false)'''
)
replace_once(
    child_main,
    '''    private fun isLocationEnabled(): Boolean = try {''',
    '''    private fun requestAlwaysActiveMode() {\n        try {\n            val activity = getSystemService(ActivityManager::class.java)\n            val restricted = Build.VERSION.SDK_INT >= 28 && activity.isBackgroundRestricted\n            val power = getSystemService(PowerManager::class.java)\n            val exempt = Build.VERSION.SDK_INT < 23 || power.isIgnoringBatteryOptimizations(packageName)\n            val intent = when {\n                restricted -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))\n                Build.VERSION.SDK_INT >= 23 && !exempt -> Intent(\n                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,\n                    Uri.parse("package:$packageName")\n                )\n                else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))\n            }\n            startActivity(intent)\n        } catch (_: Exception) {\n            openAppSettings()\n        }\n    }\n\n    private fun openSamsungNeverSleeping() {\n        getSharedPreferences("tracking_diag", MODE_PRIVATE).edit()\n            .putBoolean("samsung_never_sleeping_setup_opened", true)\n            .apply()\n        samsungNeverSleepingSetupNeeded = false\n        val samsungIntent = Intent("com.samsung.android.sm.ACTION_OPEN_CHECKABLE_LISTACTIVITY")\n            .setPackage("com.samsung.android.lool")\n            .putExtra("activity_type", 2)\n        try {\n            startActivity(samsungIntent)\n        } catch (_: Exception) {\n            try { startActivity(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)) }\n            catch (_: Exception) { openAppSettings() }\n        }\n    }\n\n    private fun isLocationEnabled(): Boolean = try {'''
)
replace_once(
    child_main,
    '''    showLocationReminder: Boolean,\n    onPermissionSettings: () -> Unit,\n    onLocationSettings: () -> Unit''',
    '''    showLocationReminder: Boolean,\n    backgroundProtectionNeeded: Boolean,\n    samsungNeverSleepingSetupNeeded: Boolean,\n    onPermissionSettings: () -> Unit,\n    onBackgroundProtection: () -> Unit,\n    onSamsungNeverSleeping: () -> Unit,\n    onLocationSettings: () -> Unit'''
)
replace_once(
    child_main,
    '''                if (setupMessage != null) {''',
    '''                if (backgroundProtectionNeeded || samsungNeverSleepingSetupNeeded) {\n                    Spacer(Modifier.height(16.dp))\n                    Card(\n                        modifier = Modifier.fillMaxWidth(),\n                        colors = CardDefaults.cardColors(containerColor = Color(0xFF122019)),\n                        shape = MaterialTheme.shapes.extraLarge\n                    ) {\n                        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {\n                            Text("Hoạt động nền", color = Color(0xFF76D79A), style = MaterialTheme.typography.labelMedium)\n                            Spacer(Modifier.height(6.dp))\n                            Text(\n                                "Để vị trí tiếp tục hoạt động khi màn hình tắt, hãy cho phép ứng dụng chạy không hạn chế và trên Samsung thêm ứng dụng vào danh sách Không bao giờ tự nghỉ.",\n                                color = Color(0xFFC8D8D0),\n                                style = MaterialTheme.typography.bodySmall,\n                                textAlign = TextAlign.Center\n                            )\n                            if (backgroundProtectionNeeded) {\n                                Spacer(Modifier.height(8.dp))\n                                Button(onClick = onBackgroundProtection) { Text("CHO PHÉP HOẠT ĐỘNG LIÊN TỤC") }\n                            }\n                            if (samsungNeverSleepingSetupNeeded) {\n                                Spacer(Modifier.height(6.dp))\n                                TextButton(onClick = onSamsungNeverSleeping) { Text("MỞ DANH SÁCH KHÔNG BAO GIỜ TỰ NGHỈ") }\n                            }\n                        }\n                    }\n                }\n\n                if (setupMessage != null) {'''
)

# -----------------------------------------------------------------------------
# LOCATION SERVICE: one-minute local liveness heartbeat, service instance id,
# restart counters, GPS callback timestamp, watchdog scheduling, and power state.
# -----------------------------------------------------------------------------
replace_once(
    loc,
    "import android.app.*\nimport android.content.Intent",
    "import android.app.*\nimport android.app.usage.UsageStatsManager\nimport android.content.Intent"
)
replace_once(
    loc,
    "import android.os.Looper\nimport android.os.SystemClock",
    "import android.os.Looper\nimport android.os.PowerManager\nimport android.os.SystemClock"
)
replace_once(
    loc,
    "import java.util.concurrent.Executors",
    "import java.util.UUID\nimport java.util.concurrent.Executors"
)
replace_once(
    loc,
    '''    private lateinit var failoverManager: NetworkFailoverManager\n    @Volatile private var pendingEventCount = 0''',
    '''    private lateinit var failoverManager: NetworkFailoverManager\n    private var serviceInstanceId = ""\n    private var serviceStartCount = 0\n    @Volatile private var lastLocalServiceHeartbeatAt = 0L\n    @Volatile private var lastGpsCallbackAt = 0L\n    @Volatile private var lastCloudHeartbeatAttemptAt = 0L\n    @Volatile private var pendingEventCount = 0'''
)
replace_once(
    loc,
    '''    private val handler = Handler(Looper.getMainLooper())\n\n    private val heartbeat = object : Runnable {''',
    '''    private val handler = Handler(Looper.getMainLooper())\n\n    private val localServiceHeartbeat = object : Runnable {\n        override fun run() {\n            writeLocalServiceHeartbeat(System.currentTimeMillis())\n            handler.postDelayed(this, LOCAL_SERVICE_HEARTBEAT_MS)\n        }\n    }\n\n    private val heartbeat = object : Runnable {'''
)
replace_once(
    loc,
    '''    private val callback = object : LocationCallback() {\n        override fun onLocationResult(result: LocationResult) {\n            result.locations.forEach { handleCandidate(it, null) }''',
    '''    private val callback = object : LocationCallback() {\n        override fun onLocationResult(result: LocationResult) {\n            lastGpsCallbackAt = System.currentTimeMillis()\n            result.locations.forEach { handleCandidate(it, null) }'''
)
replace_once(
    loc,
    '''        serviceStartedAt = System.currentTimeMillis()\n        client = LocationServices.getFusedLocationProviderClient(this)''',
    '''        serviceStartedAt = System.currentTimeMillis()\n        serviceInstanceId = UUID.randomUUID().toString()\n        val servicePrefs = getSharedPreferences("tracking_diag", MODE_PRIVATE)\n        serviceStartCount = servicePrefs.getInt(KEY_SERVICE_START_COUNT, 0) + 1\n        servicePrefs.edit()\n            .putInt(KEY_SERVICE_START_COUNT, serviceStartCount)\n            .putString(KEY_SERVICE_INSTANCE_ID, serviceInstanceId)\n            .putLong(KEY_SERVICE_STARTED_AT_LOCAL, serviceStartedAt)\n            .apply()\n        writeLocalServiceHeartbeat(serviceStartedAt)\n        ServiceWatchdogWorker.schedule(this)\n        client = LocationServices.getFusedLocationProviderClient(this)'''
)
replace_once(
    loc,
    '''        handler.postDelayed(heartbeat, HEARTBEAT_MS)\n        handler.postDelayed(fallbackPoll, 3_000L)''',
    '''        handler.postDelayed(localServiceHeartbeat, LOCAL_SERVICE_HEARTBEAT_MS)\n        handler.postDelayed(heartbeat, HEARTBEAT_MS)\n        handler.postDelayed(fallbackPoll, 3_000L)'''
)
replace_once(
    loc,
    '''        if (intent?.getBooleanExtra("immediate", false) == true) {''',
    '''        if (intent?.getBooleanExtra(ServiceWatchdogWorker.EXTRA_WATCHDOG_RECOVERY, false) == true) {\n            publishLocalStatus("watchdog_recovery_start")\n            writeLocalServiceHeartbeat(System.currentTimeMillis())\n            evaluateTrackingHealth()\n            if (::failoverManager.isInitialized) failoverManager.probeNow()\n        }\n        if (intent?.getBooleanExtra("immediate", false) == true) {'''
)
replace_once(
    loc,
    '''    private fun publishHeartbeat() {\n        val now = System.currentTimeMillis()\n        maintainTrackingNotification(now)''',
    '''    private fun publishHeartbeat() {\n        val now = System.currentTimeMillis()\n        lastCloudHeartbeatAttemptAt = now\n        maintainTrackingNotification(now)'''
)
replace_once(
    loc,
    '''            "cellularAvailable" to failover.cellularAvailable\n        )''',
    '''            "cellularAvailable" to failover.cellularAvailable,\n            "serviceInstanceId" to serviceInstanceId,\n            "serviceStartCount" to serviceStartCount,\n            "serviceRestartCount" to (serviceStartCount - 1).coerceAtLeast(0),\n            "lastLocalServiceHeartbeatAt" to lastLocalServiceHeartbeatAt,\n            "lastGpsCallbackAt" to lastGpsCallbackAt,\n            "lastCloudHeartbeatAttemptAt" to lastCloudHeartbeatAttemptAt,\n            "batteryOptimizationIgnored" to isBatteryOptimizationIgnored(),\n            "backgroundRestricted" to isBackgroundRestricted(),\n            "appStandbyRestricted" to isAppStandbyRestricted(),\n            "watchdogLastRunAt" to getSharedPreferences("tracking_diag", MODE_PRIVATE).getLong(ServiceWatchdogWorker.KEY_WATCHDOG_LAST_RUN_AT, 0L),\n            "watchdogResult" to getSharedPreferences("tracking_diag", MODE_PRIVATE).getString(ServiceWatchdogWorker.KEY_WATCHDOG_RESULT, "unknown")\n        )'''
)
replace_once(
    loc,
    '''    private fun restartCloudAfterRouteChange() {''',
    '''    private fun writeLocalServiceHeartbeat(now: Long) {\n        lastLocalServiceHeartbeatAt = now\n        getSharedPreferences("tracking_diag", MODE_PRIVATE).edit()\n            .putLong(ServiceWatchdogWorker.KEY_LAST_LOCAL_HEARTBEAT_AT, now)\n            .putString(KEY_SERVICE_INSTANCE_ID, serviceInstanceId)\n            .putBoolean(ServiceWatchdogWorker.KEY_BATTERY_OPTIMIZATION_IGNORED, isBatteryOptimizationIgnored())\n            .putBoolean(ServiceWatchdogWorker.KEY_BACKGROUND_RESTRICTED, isBackgroundRestricted())\n            .apply()\n    }\n\n    private fun isBatteryOptimizationIgnored(): Boolean = try {\n        Build.VERSION.SDK_INT < 23 || getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)\n    } catch (_: Exception) { false }\n\n    private fun isBackgroundRestricted(): Boolean = try {\n        Build.VERSION.SDK_INT >= 28 && getSystemService(ActivityManager::class.java).isBackgroundRestricted\n    } catch (_: Exception) { false }\n\n    private fun isAppStandbyRestricted(): Boolean = try {\n        Build.VERSION.SDK_INT >= 28 && getSystemService(UsageStatsManager::class.java).appStandbyBucket == UsageStatsManager.STANDBY_BUCKET_RESTRICTED\n    } catch (_: Exception) { false }\n\n    private fun restartCloudAfterRouteChange() {'''
)
replace_once(
    loc,
    '''        handler.removeCallbacks(heartbeat)\n        handler.removeCallbacks(fallbackPoll)''',
    '''        handler.removeCallbacks(localServiceHeartbeat)\n        handler.removeCallbacks(heartbeat)\n        handler.removeCallbacks(fallbackPoll)'''
)
replace_once(
    loc,
    '''        if (::failoverManager.isInitialized) failoverManager.stop()\n        if (trackingActive)''',
    '''        if (::failoverManager.isInitialized) failoverManager.stop()\n        getSharedPreferences("tracking_diag", MODE_PRIVATE).edit()\n            .putLong(KEY_SERVICE_DESTROYED_AT, System.currentTimeMillis())\n            .apply()\n        if (trackingActive)'''
)
replace_once(
    loc,
    '''        private const val FIREBASE_HEALTH_THROTTLE_MS = 60_000L''',
    '''        private const val LOCAL_SERVICE_HEARTBEAT_MS = 60_000L\n        private const val KEY_SERVICE_START_COUNT = "service_start_count"\n        private const val KEY_SERVICE_INSTANCE_ID = "service_instance_id"\n        private const val KEY_SERVICE_STARTED_AT_LOCAL = "service_started_at_local"\n        private const val KEY_SERVICE_DESTROYED_AT = "service_destroyed_at"\n        private const val FIREBASE_HEALTH_THROTTLE_MS = 60_000L'''
)

# -----------------------------------------------------------------------------
# Boot: persist the periodic watchdog even after reboot/app replacement.
# -----------------------------------------------------------------------------
replace_once(
    boot,
    '''        if (!allowedAction) return\n\n        val fine =''',
    '''        if (!allowedAction) return\n\n        ServiceWatchdogWorker.schedule(context)\n\n        val fine ='''
)

# -----------------------------------------------------------------------------
# Parent REST schema receives survival diagnostics even if realtime Firestore is
# the failing path.
# -----------------------------------------------------------------------------
replace_once(
    parent_https,
    '''        val routeMode: String? = null,\n        val cellularFailoverActive: Boolean? = null,''',
    '''        val routeMode: String? = null,\n        val serviceInstanceId: String? = null,\n        val serviceStartCount: Long = 0L,\n        val serviceRestartCount: Long = 0L,\n        val lastLocalServiceHeartbeatAt: Long = 0L,\n        val lastGpsCallbackAt: Long = 0L,\n        val lastCloudHeartbeatAttemptAt: Long = 0L,\n        val batteryOptimizationIgnored: Boolean? = null,\n        val backgroundRestricted: Boolean? = null,\n        val appStandbyRestricted: Boolean? = null,\n        val watchdogLastRunAt: Long = 0L,\n        val watchdogResult: String? = null,\n        val cellularFailoverActive: Boolean? = null,'''
)
replace_once(
    parent_https,
    '''                                routeMode = string(fields, "routeMode"),\n                                cellularFailoverActive = bool(fields, "cellularFailoverActive"),''',
    '''                                routeMode = string(fields, "routeMode"),\n                                serviceInstanceId = string(fields, "serviceInstanceId"),\n                                serviceStartCount = long(fields, "serviceStartCount"),\n                                serviceRestartCount = long(fields, "serviceRestartCount"),\n                                lastLocalServiceHeartbeatAt = long(fields, "lastLocalServiceHeartbeatAt"),\n                                lastGpsCallbackAt = long(fields, "lastGpsCallbackAt"),\n                                lastCloudHeartbeatAttemptAt = long(fields, "lastCloudHeartbeatAttemptAt"),\n                                batteryOptimizationIgnored = bool(fields, "batteryOptimizationIgnored"),\n                                backgroundRestricted = bool(fields, "backgroundRestricted"),\n                                appStandbyRestricted = bool(fields, "appStandbyRestricted"),\n                                watchdogLastRunAt = long(fields, "watchdogLastRunAt"),\n                                watchdogResult = string(fields, "watchdogResult"),\n                                cellularFailoverActive = bool(fields, "cellularFailoverActive"),'''
)

# -----------------------------------------------------------------------------
# Parent: distinguish stale last-known values from current evidence and make a
# prolonged no-heartbeat episode explicitly point at service/background sleep.
# -----------------------------------------------------------------------------
replace_once(
    parent,
    '''private const val DIAGNOSTIC_FRESH_MS = 10 * 60_000L''',
    '''private const val DIAGNOSTIC_FRESH_MS = 10 * 60_000L\nprivate const val SERVICE_SUSPECT_STALE_MS = 20 * 60_000L'''
)
replace_once(
    parent,
    '''    var routeMode by remember { mutableStateOf<String?>(null) }\n    var cellularFailoverActive by remember { mutableStateOf(false) }''',
    '''    var routeMode by remember { mutableStateOf<String?>(null) }\n    var serviceInstanceId by remember { mutableStateOf<String?>(null) }\n    var serviceRestartCount by remember { mutableLongStateOf(0L) }\n    var lastLocalServiceHeartbeatAt by remember { mutableLongStateOf(0L) }\n    var lastGpsCallbackAt by remember { mutableLongStateOf(0L) }\n    var batteryOptimizationIgnored by remember { mutableStateOf<Boolean?>(null) }\n    var backgroundRestricted by remember { mutableStateOf<Boolean?>(null) }\n    var appStandbyRestricted by remember { mutableStateOf<Boolean?>(null) }\n    var watchdogLastRunAt by remember { mutableLongStateOf(0L) }\n    var watchdogResult by remember { mutableStateOf<String?>(null) }\n    var cellularFailoverActive by remember { mutableStateOf(false) }'''
)
replace_once(
    parent,
    '''        routeMode = s.routeMode ?: routeMode\n        cellularFailoverActive = s.cellularFailoverActive ?: cellularFailoverActive''',
    '''        routeMode = s.routeMode ?: routeMode\n        serviceInstanceId = s.serviceInstanceId ?: serviceInstanceId\n        serviceRestartCount = s.serviceRestartCount\n        if (s.lastLocalServiceHeartbeatAt > 0L) lastLocalServiceHeartbeatAt = s.lastLocalServiceHeartbeatAt\n        if (s.lastGpsCallbackAt > 0L) lastGpsCallbackAt = s.lastGpsCallbackAt\n        if (s.batteryOptimizationIgnored != null) batteryOptimizationIgnored = s.batteryOptimizationIgnored\n        if (s.backgroundRestricted != null) backgroundRestricted = s.backgroundRestricted\n        if (s.appStandbyRestricted != null) appStandbyRestricted = s.appStandbyRestricted\n        if (s.watchdogLastRunAt > 0L) watchdogLastRunAt = s.watchdogLastRunAt\n        watchdogResult = s.watchdogResult ?: watchdogResult\n        cellularFailoverActive = s.cellularFailoverActive ?: cellularFailoverActive'''
)
replace_once(
    parent,
    '''                routeMode = d.getString("routeMode") ?: routeMode\n                cellularFailoverActive = d.getBoolean("cellularFailoverActive") ?: cellularFailoverActive''',
    '''                routeMode = d.getString("routeMode") ?: routeMode\n                serviceInstanceId = d.getString("serviceInstanceId") ?: serviceInstanceId\n                serviceRestartCount = d.getLong("serviceRestartCount") ?: serviceRestartCount\n                lastLocalServiceHeartbeatAt = d.getLong("lastLocalServiceHeartbeatAt") ?: lastLocalServiceHeartbeatAt\n                lastGpsCallbackAt = d.getLong("lastGpsCallbackAt") ?: lastGpsCallbackAt\n                batteryOptimizationIgnored = d.getBoolean("batteryOptimizationIgnored") ?: batteryOptimizationIgnored\n                backgroundRestricted = d.getBoolean("backgroundRestricted") ?: backgroundRestricted\n                appStandbyRestricted = d.getBoolean("appStandbyRestricted") ?: appStandbyRestricted\n                watchdogLastRunAt = d.getLong("watchdogLastRunAt") ?: watchdogLastRunAt\n                watchdogResult = d.getString("watchdogResult") ?: watchdogResult\n                cellularFailoverActive = d.getBoolean("cellularFailoverActive") ?: cellularFailoverActive'''
)
replace_once(
    parent,
    '''    val assessment = when {\n        cellularFailoverActive && baseAssessment.level != ConnectionLevel.LOST ->''',
    '''    val networkAssessment = when {\n        cellularFailoverActive && baseAssessment.level != ConnectionLevel.LOST ->'''
)
replace_once(
    parent,
    '''        else -> baseAssessment\n    }\n    val locationStateFresh =''',
    '''        else -> baseAssessment\n    }\n    val lastServiceEvidenceAt = maxOf(lastLocalServiceHeartbeatAt, heartbeatAt, diagnosticAt)\n    val serviceLikelyStopped = networkAssessment.level == ConnectionLevel.LOST &&\n        lastServiceEvidenceAt > 0L && now - lastServiceEvidenceAt >= SERVICE_SUSPECT_STALE_MS\n    val assessment = if (serviceLikelyStopped) {\n        networkAssessment.copy(\n            title = "Dịch vụ Máy Con có thể đã dừng",\n            detail = "Không nhận được heartbeat của ứng dụng trong ${ageAt(now, lastServiceEvidenceAt)}. Có thể Samsung đã giới hạn hoặc cho ứng dụng nghỉ sâu; trạng thái GPS/mạng bên dưới chỉ là dữ liệu cuối cùng đã biết."\n        )\n    } else if (backgroundRestricted == true || appStandbyRestricted == true) {\n        networkAssessment.copy(\n            level = if (networkAssessment.level == ConnectionLevel.CONNECTED) ConnectionLevel.WARNING else networkAssessment.level,\n            title = if (networkAssessment.level == ConnectionLevel.CONNECTED) "Đang kết nối · nền bị hạn chế" else networkAssessment.title,\n            detail = "Máy Con báo Android đang hạn chế hoạt động nền. Hãy đặt Pin = Không hạn chế và trên Samsung thêm app vào Không bao giờ tự nghỉ."\n        )\n    } else networkAssessment\n    val locationStateFresh ='''
)
replace_once(
    parent,
    '''                StatusMiniCard("GPS", when (locationEnabled) { true -> "Bật"; false -> "Tắt"; else -> "Chưa rõ" }, if (locationEnabled == true) Color(0xFF76D79A) else Color(0xFFFFC857), Modifier.weight(1f))\n                StatusMiniCard("Dữ liệu", if (usingFallback) "Dự phòng" else if (deviceFromCache) "Lưu tạm" else "Trực tiếp", if (deviceFromCache && !usingFallback) Color(0xFFFFC857) else Color(0xFF76D79A), Modifier.weight(1f))''',
    '''                val evidenceFresh = assessment.lastContactAt > 0L && System.currentTimeMillis() - assessment.lastContactAt <= DIAGNOSTIC_FRESH_MS\n                val gpsText = when (locationEnabled) {\n                    true -> if (evidenceFresh) "Bật" else "Bật · ${age(assessment.lastContactAt)} trước"\n                    false -> if (evidenceFresh) "Tắt" else "Tắt · ${age(assessment.lastContactAt)} trước"\n                    else -> "Chưa rõ"\n                }\n                StatusMiniCard("GPS", gpsText, if (locationEnabled == true && evidenceFresh) Color(0xFF76D79A) else Color(0xFFFFC857), Modifier.weight(1f))\n                val dataText = when {\n                    !evidenceFresh -> "Cũ · ${age(assessment.lastContactAt)}"\n                    usingFallback -> "Dự phòng"\n                    deviceFromCache -> "Lưu tạm"\n                    else -> "Trực tiếp"\n                }\n                StatusMiniCard("Dữ liệu", dataText, if (!evidenceFresh || (deviceFromCache && !usingFallback)) Color(0xFFFFC857) else Color(0xFF76D79A), Modifier.weight(1f))'''
)

checks = {
    child_manifest: ["android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"],
    str(watchdog_dst): ["PeriodicWorkRequestBuilder<ServiceWatchdogWorker>", "restart_requested", "startForegroundService"],
    child_main: [
        "CHO PHÉP HOẠT ĐỘNG LIÊN TỤC",
        "com.samsung.android.sm.ACTION_OPEN_CHECKABLE_LISTACTIVITY",
        'putExtra("activity_type", 2)',
        "ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
        "ServiceWatchdogWorker.schedule(this)",
    ],
    loc: [
        "lastLocalServiceHeartbeatAt",
        "serviceInstanceId",
        "serviceRestartCount",
        "ServiceWatchdogWorker.schedule(this)",
        "watchdog_recovery_start",
        "batteryOptimizationIgnored",
        "appStandbyRestricted",
        "LOCAL_SERVICE_HEARTBEAT_MS = 60_000L",
        "NetworkFailoverManager(this)",
        "queueNetworkEvent(\"network_offline\"",
    ],
    parent_https: ["lastLocalServiceHeartbeatAt", "batteryOptimizationIgnored", "watchdogResult"],
    parent: [
        "Dịch vụ Máy Con có thể đã dừng",
        "SERVICE_SUSPECT_STALE_MS",
        "Không bao giờ tự nghỉ",
        "Bật · ${age(assessment.lastContactAt)} trước",
        "cellularFailoverActive",
    ],
}
for path, needles in checks.items():
    text = Path(path).read_text(encoding="utf-8")
    for needle in needles:
        if needle not in text:
            raise SystemExit(f"v2.2.6 validation missing {needle!r} in {path}")

print("v2.2.6 service survival patch applied")
