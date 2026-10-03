from pathlib import Path
import shutil

child=Path('appsrc/child-app/src/main/java/com/family/child')
parent=Path('appsrc/parent-app/src/main/java/com/family/parent')
for name in ['TokenState','WakeTokenSyncWorker']:
    shutil.copyfile(f'ci-patches/{name}V2211.kt',child/f'{name}.kt')
for name in ['ParentWakeBridge','SurvivalHealth']:
    shutil.copyfile(f'ci-patches/{name}V2211.kt',parent/f'{name}.kt')
shutil.copyfile('ci-patches/SurvivalReliabilityTestV2211.kt','appsrc/child-app/src/test/java/com/family/child/SurvivalReliabilityTest.kt')
def edit(path,old,new,count=1):
    s=path.read_text(encoding='utf-8')
    assert old in s, f'v2211 missing anchor: {path}: {old[:90]}'
    path.write_text(s.replace(old,new,count),encoding='utf-8')

loc=child/'LocationService.kt'
edit(loc,'    private var listenerRetryAt = 0L','    private var listenerRetryAt = 0L\n    private var listenerGeneration = 0L\n    private var routeGeneration = 0L\n    private var lastFreshFixAttemptElapsed = 0L')
edit(loc,'        super.onCreate()\n        serviceStartedAt', '''        super.onCreate()
        // Meet the promotion deadline before database/SDK/job/geofence work.
        try { startAsForeground(); running = true }
        catch (e: Exception) { publishLocalStatus("foreground_start_failed:${e.javaClass.simpleName}"); stopSelf(); return }
        serviceStartedAt''')
edit(loc,'''        try {
            startAsForeground()
            running = true
        } catch (e: Exception) {
            publishLocalStatus("foreground_start_failed:${e.javaClass.simpleName}")
            stopSelf()
            return
        }
''','')
edit(loc,'        val fine = hasFineLocation()\n        val locationOn = isLocationEnabled()\n        val signature', '''        val fine = hasFineLocation()
        val locationOn = isLocationEnabled()
        val elapsed = SystemClock.elapsedRealtime()
        // A distance-filtered stationary callback can legitimately be quiet.
        // Infrequent fresh acquisition checks the provider without claiming it died.
        if (fine && locationOn && trackingActive && !acquiring && elapsed - lastFreshFixAttemptElapsed >= 10 * 60_000L &&
            System.currentTimeMillis() - lastGpsCallbackAt >= 10 * 60_000L) requestImmediate(null)
        val signature''')
edit(loc,'        commandListener?.remove()\n        commandListener = cloud.collection', '        val generation = ++listenerGeneration\n        commandListener?.remove()\n        commandListener = cloud.collection')
edit(loc,'            .addSnapshotListener(MetadataChanges.INCLUDE) { d, error ->\n                if (error', '            .addSnapshotListener(MetadataChanges.INCLUDE) { d, error ->\n                if (destroyed || generation != listenerGeneration) return@addSnapshotListener\n                if (error')
edit(loc,'        authRetryAt = 0L\n        authInFlight = false','        val generation = ++routeGeneration\n        ++listenerGeneration\n        authRetryAt = 0L\n        authInFlight = false')
edit(loc,'if (destroyed) return@addOnCompleteListener','if (destroyed || generation != routeGeneration) return@addOnCompleteListener',2)
edit(loc,'        val token = CancellationTokenSource()','        lastFreshFixAttemptElapsed = SystemClock.elapsedRealtime()\n        val token = CancellationTokenSource()')
edit(loc,'            "fcmDeliveredPriority" to','            "fcmOriginalPriority" to getSharedPreferences("tracking_diag", MODE_PRIVATE).getInt("fcm_wake_original_priority_v229", 0),\n            "protectionChannelEnabled" to isNotificationChannelEnabled(PROTECTION_CHANNEL_ID),\n            "wakeNotificationEnabled" to getSharedPreferences("tracking_diag", MODE_PRIVATE).getBoolean("protection_notification_enabled_v229", false),\n            "unusedAppRestricted" to unusedRestrictionEnabled(),\n            "unusedAppRestrictionMeaning" to "auto_reset_enabled_not_current_hibernation",\n            "fcmDeliveredPriority" to')
edit(loc,'    private fun isBatteryOptimizationIgnored()', '''    private fun unusedRestrictionEnabled(): Boolean? {
        val p = getSharedPreferences("tracking_diag", MODE_PRIVATE)
        if (!p.contains("unused_app_restrictions_checked_at_v229")) return null
        val status = p.getInt("unused_app_restrictions_status_v229", androidx.core.content.UnusedAppRestrictionsConstants.ERROR)
        if (status == androidx.core.content.UnusedAppRestrictionsConstants.ERROR) return null
        return p.getBoolean("unused_app_restrictions_enabled_v229", false)
    }

    private fun isBatteryOptimizationIgnored()''')
# Persist terminal GPS response even if auth disappears while acquiring. The
# existing retryRefreshResult delivers it after authentication is restored.
edit(loc,'''        if (FirebaseAuth.getInstance().currentUser != null) {
            val diag = diagnosticFields(now)
            val payload = mutableMapOf<String, Any?>(''','''        val diag = diagnosticFields(now)
        val payload = mutableMapOf<String, Any?>(''')
edit(loc,'''            if (refreshFor != null) saveRefreshResult(refreshFor, payload)
            val firebaseStart''','''        if (refreshFor != null) saveRefreshResult(refreshFor, payload)
        if (FirebaseAuth.getInstance().currentUser != null) {
            val firebaseStart''')
contents=loc.read_text(encoding='utf-8')
start=contents.index('        val diag = diagnosticFields(now)',contents.index('RecoveryGeofenceManager.update(this, l)'))
end=contents.index('        if (refreshFor != null) saveRefreshResult',start)
contents=contents[:start]+'\n'.join(line[4:] if line.startswith('            ') else line for line in contents[start:end].split('\n'))+contents[end:]
loc.write_text(contents,encoding='utf-8')
edit(loc,'        commandListener?.remove()\n        handler.removeCallbacks(localServiceHeartbeat)', '        ++listenerGeneration; ++routeGeneration\n        commandListener?.remove()\n        handler.removeCallbacks(localServiceHeartbeat)')

boot=child/'BootReceiver.kt'
edit(boot,'            intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||\n','')
edit(boot,'        ServiceWatchdogWorker.schedule(context)','        val pending = goAsync()\n        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ pending.finish() }, 8_000L)\n        ServiceWatchdogWorker.schedule(context)')
edit(boot,'RecoveryGeofenceManager.rearmLatestObserved(context, "boot")','RecoveryGeofenceManager.rearmLatestObserved(context, if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) "package_replace" else "boot")')
edit(boot,'ContextCompat.startForegroundService(context, Intent(context, LocationService::class.java))','if (!RecoveryStarter.startLocationService(context, if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) "package_replace" else "boot")) ServiceWatchdogWorker.requestImmediateRecovery(context, "boot_start_failed")')

watchdog=child/'ServiceWatchdogWorker.kt'
edit(watchdog,'        val app = applicationContext','        if (runAttemptCount >= 8) return Result.failure()\n        val app = applicationContext')
edit(watchdog,'''            val request = OneTimeWorkRequestBuilder<ServiceWatchdogWorker>()
                .setInputData(workDataOf(KEY_RECOVERY_REASON to reason))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()''','''            val builder = OneTimeWorkRequestBuilder<ServiceWatchdogWorker>()
                .setInputData(workDataOf(KEY_RECOVERY_REASON to reason))
            // Pre-31 expedited work requires ForegroundInfo, which this watchdog
            // intentionally does not provide. Use the regular persisted job there.
            if (Build.VERSION.SDK_INT >= 31) builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            val request = builder.build()''')

core=Path('appsrc/core/src/main/kotlin/com/family/core/VisitEngine.kt')
edit(core,'                t.points += sample','                // History lives in the journal; keep only the current anchor in RAM.\n                t.points.clear()\n                t.points += sample')

main=parent/'MainActivity.kt'
edit(main,'    var refreshText by remember', '    var survivalDiagnostics by remember { mutableStateOf<Map<String, String>>(emptyMap()) }\n    var wakeProgressRank by remember { mutableIntStateOf(0) }\n    var refreshText by remember')
edit(main,'    fun applyRestState(s: ParentHttpsBridge.DeviceState) {','    fun applyRestState(s: ParentHttpsBridge.DeviceState) {\n        survivalDiagnostics = s.survivalDiagnostics')
edit(main,'                if (!d.exists()) return@addSnapshotListener','                if (!d.exists()) return@addSnapshotListener\n                survivalDiagnostics = SurvivalHealth.from(d.data ?: emptyMap())')
edit(main,'            s.wakeDispatchFor -> if (s.wakeDispatchResult == "sent") "Backend đã phát yêu cầu đánh thức..." else "Kênh đánh thức: ${s.wakeDispatchResult ?: "đang chờ"}"','            s.wakeDispatchFor -> if (s.wakeDispatchResult == "sent") "FCM đã gửi · chờ Máy Con nhận..." else "Kênh đánh thức: ${s.wakeDispatchResult ?: "đang chờ"}"\n            s.wakeBackendFor -> "Wake backend đã nhận yêu cầu..."')
edit(main,'                val wakeFor = d.getLong("wakeDispatchFor") ?: 0L', '                if (refreshRequestId > 0L && d.getLong("wakeBackendFor") == refreshRequestId && (d.getLong("refreshReceivedFor") ?: 0L) != refreshRequestId) {\n                    wakeProgressRank = maxOf(wakeProgressRank, 2); refreshText = "Wake backend đã nhận yêu cầu..."\n                }\n                val wakeFor = d.getLong("wakeDispatchFor") ?: 0L')
edit(main,'wakeResult == "sent" -> "Đã gửi yêu cầu đánh thức Máy Con · chờ phản hồi..."','wakeResult == "sent" -> "FCM đã gửi · chờ Máy Con nhận..."')
# Direct Worker replies cannot regress later Child progress.
edit(main,'        val request = refreshRequestId\n        if (request <= 0L) return', '        val request = refreshRequestId\n        if (request <= 0L) return\n        if (ack == request || completed == request || failed == request) wakeProgressRank = maxOf(wakeProgressRank, 4)')
edit(main,'"Đang lấy vị trí mới..."','"Đang lấy GPS mới..."',2)
edit(main,'            repeat(15) { index ->\n                delay(3_000L)','            repeat(4) { index ->\n                delay(10_000L)')
edit(main,'            repeat(15) {\n                delay(3_000L)','            repeat(4) {\n                delay(10_000L)')
edit(main,'            if (refreshRequestId == request) {\n                refreshText = "Máy Con chưa phản hồi sau 45 giây"','            delay(5_000L)\n            if (refreshRequestId == request) {\n                refreshText = "Máy Con chưa phản hồi sau 45 giây"')
edit(main,'            if (reminderRequestId == request) {\n                reminderText = "Chưa xác nhận','            delay(5_000L)\n            if (reminderRequestId == request) {\n                reminderText = "Chưa xác nhận')
edit(main,'if (deviceFromCache || serverStale || refreshRequestId > 0L || reminderRequestId > 0L) {','if ((deviceFromCache || serverStale) && refreshRequestId == 0L && reminderRequestId == 0L) {')
edit(main,'                    commandConfirmedFor = 0L\n                    refreshText', '                    commandConfirmedFor = 0L\n                    wakeProgressRank = 0\n                    val parentUid = FirebaseAuth.getInstance().currentUser?.uid ?: return@HomeScreen\n                    refreshText')
edit(main,'val payload = mapOf<String, Any?>("refreshRequestedAt" to requestAt, "refreshExpiresAt" to requestAt + 15 * 60_000L)','val payload = mapOf<String, Any?>("refreshRequestedAt" to requestAt, "refreshExpiresAt" to requestAt + 15 * 60_000L, "refreshRequestedBy" to parentUid)')
edit(main,'db.collection("devices").document(CHILD_DOC).set(payload, SetOptions.merge())\n                        .addOnSuccessListener {','ParentWakeBridge.prepareCommand(context, requestAt, parentUid)\n                        .addOnSuccessListener { written ->\n                            if (!written) return@addOnSuccessListener')
edit(main,'ParentHttpsBridge.patch(payload) { ok, error ->','ParentWakeBridge.persistFallback(requestAt, parentUid) { ok, error ->')
edit(main,'                                        refreshText = "Đã gửi qua HTTPS dự phòng · chờ Máy Con phản hồi..."','                                        if (wakeProgressRank < 1) { wakeProgressRank = 1; refreshText = "Yêu cầu đã gửi qua HTTPS · chờ wake backend..." }\n                                        ParentWakeBridge.dispatch(requestAt) { result ->\n                                            if (refreshRequestId == requestAt && wakeProgressRank < 2 && result.accepted) { wakeProgressRank = 2; refreshText = "Wake backend đã nhận yêu cầu..." }\n                                        }')
edit(main,'                                refreshText = "Đã gửi yêu cầu · chờ Máy Con phản hồi..."','''                                if (wakeProgressRank < 1) { wakeProgressRank = 1; refreshText = "Yêu cầu đã gửi · chờ wake backend..." }
                                ParentWakeBridge.dispatch(requestAt) { result ->
                                    if (refreshRequestId == requestAt && wakeProgressRank < 4) {
                                        if (result.accepted && wakeProgressRank < 2) { wakeProgressRank = 2; refreshText = "Wake backend đã nhận yêu cầu..." }
                                        else if (!result.accepted) refreshText = if (result.status == "not_configured") "Chưa cấu hình Cloudflare Worker · vẫn chờ kênh nền..." else "Wake backend: ${result.status} · vẫn chờ Máy Con..."
                                    }
                                }''')
edit(main,'                parentHttpsLatencyMs = ParentHttpsBridge.lastLatencyMs','                parentHttpsLatencyMs = ParentHttpsBridge.lastLatencyMs,\n                survivalDiagnostics = survivalDiagnostics')
edit(main,'    parentHttpsLatencyMs: Long\n)', '    parentHttpsLatencyMs: Long,\n    survivalDiagnostics: Map<String, String>\n)')
edit(main,'            HealthCard("Ứng dụng Máy Con") {','''            HealthCard("Survival / Android background") {
                HealthRow("Firebase UID Máy Cha", FirebaseAuth.getInstance().currentUser?.uid ?: "Chưa xác định")
                HealthRow("Cloudflare Worker", ParentWakeBridge.endpoint() ?: "Chưa cấu hình")
                SurvivalHealth.fields.forEach { name -> HealthRow(name, survivalDiagnostics[name] ?: "Chưa xác định") }
                Text("unusedAppRestricted cho biết tính năng tự thu hồi quyền/hibernation được bật, không chứng minh app hiện đang ngủ. Samsung Sleeping/Deep Sleeping: không có API công khai để xác nhận.", style = MaterialTheme.typography.bodySmall)
            }
        }

        item {
            HealthCard("Ứng dụng Máy Con") {''')

# One monotonic stage renderer for SDK snapshots, REST snapshots and HTTP replies.
edit(main,'    fun processReminderState(', '''    fun processWakeProgress(received: Long, service: Long, locating: Long, dispatch: Long, result: String?, backend: Long) {
        val request = refreshRequestId
        if (request <= 0L) return
        val stage = when {
            locating == request -> 6 to "Đang lấy GPS mới..."
            service == request -> 5 to "Service Máy Con đang hoạt động..."
            received == request -> 4 to "Máy Con đã nhận yêu cầu..."
            dispatch == request && result == "sent" -> 3 to "FCM đã gửi · chờ Máy Con nhận..."
            backend == request -> 2 to "Wake backend đã nhận yêu cầu..."
            else -> return
        }
        if (stage.first >= wakeProgressRank) { wakeProgressRank = stage.first; refreshText = stage.second }
    }

    fun processReminderState(''')
edit(main,'            ack == request -> refreshText','            ack == request && wakeProgressRank <= 4 -> refreshText')
edit(main,'val parentUid = FirebaseAuth.getInstance().currentUser?.uid ?: return@HomeScreen','val parentUid = FirebaseAuth.getInstance().currentUser?.uid ?: run { refreshRequestId = 0L; refreshText = "Chưa đăng nhập Firebase"; return@HomeScreen }')
contents=main.read_text(encoding='utf-8')
start=contents.index('        if (refreshRequestId > 0L) refreshText = when (refreshRequestId) {')
end=contents.index('        processReminderState(s.locationReminderAckFor',start)
contents=contents[:start]+'        processWakeProgress(s.refreshReceivedFor, s.refreshServiceFor, s.refreshLocatingFor, s.wakeDispatchFor, s.wakeDispatchResult, s.wakeBackendFor)\n'+contents[end:]
start=contents.index('                if (refreshRequestId > 0L && d.getLong("wakeBackendFor")')
end=contents.index('                processReminderState(',start)
contents=contents[:start]+'''                processWakeProgress(d.getLong("refreshReceivedFor") ?: 0L, d.getLong("refreshServiceFor") ?: 0L,
                    d.getLong("refreshLocatingFor") ?: 0L, d.getLong("wakeDispatchFor") ?: 0L,
                    d.getString("wakeDispatchResult"), d.getLong("wakeBackendFor") ?: 0L)
'''+contents[end:]
contents=contents.replace('refreshRequestId == requestAt && wakeProgressRank < 4','refreshRequestId == requestAt && wakeProgressRank < 3')
main.write_text(contents,encoding='utf-8')

bridge=parent/'ParentHttpsBridge.kt'
edit(bridge,'    private val executor = Executors.newSingleThreadExecutor()', '''    private val executor = java.util.concurrent.ThreadPoolExecutor(2, 2, 30, java.util.concurrent.TimeUnit.SECONDS, java.util.concurrent.ArrayBlockingQueue<Runnable>(16))
    private fun submit(rejected: () -> Unit, action: () -> Unit) {
        try { executor.execute(action) } catch (_: java.util.concurrent.RejectedExecutionException) { post(rejected) }
    }''')
edit(bridge,'                executor.execute {','                submit({ callback(false, "transport_busy") }) {')
edit(bridge,'                executor.execute {','                submit({ callback(null, "transport_busy") }) {',2)
edit(bridge,'        val wakeDispatchFor: Long = 0L,','        val wakeBackendFor: Long = 0L,\n        val survivalDiagnostics: Map<String, String> = emptyMap(),\n        val wakeDispatchFor: Long = 0L,')
edit(bridge,'                                wakeDispatchFor = long(fields, "wakeDispatchFor"),','''                                wakeBackendFor = long(fields, "wakeBackendFor"),
                                survivalDiagnostics = SurvivalHealth.from(SurvivalHealth.fields.associateWith { key ->
                                    val v = fields.optJSONObject(key)
                                    when { v == null || v.has("nullValue") -> null; v.has("booleanValue") -> v.getBoolean("booleanValue"); v.has("integerValue") -> v.getString("integerValue").toLongOrNull(); else -> v.optString("stringValue", "") }
                                }),
                                wakeDispatchFor = long(fields, "wakeDispatchFor"),''')
edit(bridge,'                            connectTimeout = 8_000','                            instanceFollowRedirects = false\n                            setRequestProperty("Connection", "close")\n                            useCaches = false\n                            connectTimeout = 8_000',3)

for module in ['parent-app','child-app']:
    gradle=Path('appsrc')/module/'build.gradle.kts'
    edit(gradle,'versionCode = 32','versionCode = 33')
    edit(gradle,'versionName = "2.2.10"','versionName = "2.2.11"')
    if module=='parent-app':
        edit(gradle,'android {\n', '''val wakeWorkerUrl = providers.gradleProperty("wakeWorkerUrl").orElse(providers.environmentVariable("FAMILY_LOCATION_WAKE_WORKER_URL")).orElse("").get()
require(wakeWorkerUrl.isEmpty() || (wakeWorkerUrl.startsWith("https://") && !wakeWorkerUrl.contains('"') && !wakeWorkerUrl.contains('\\\\') && !wakeWorkerUrl.contains('\\n') && !wakeWorkerUrl.contains('\\r'))) { "Worker URL must be a public HTTPS origin" }
android {
''')
        edit(gradle,'        versionCode = 33','        buildConfigField("String", "WAKE_WORKER_URL", "\\\"$wakeWorkerUrl\\\"")\n        versionCode = 33')
        with gradle.open('a',encoding='utf-8') as f: f.write('\ndependencies { implementation("androidx.work:work-runtime-ktx:2.10.5") }\n')
print('v2211 survival/Cloudflare integration patch applied')
