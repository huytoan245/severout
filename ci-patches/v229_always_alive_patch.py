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
        raise SystemExit(f"v2.2.9 anchor not found in {path}: {old[:180]!r}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


child_root = Path("appsrc/child-app/src/main/java/com/family/child")
child_root.mkdir(parents=True, exist_ok=True)

copy_map = {
    "ci-patches/ProtectionNotifierV229.kt": child_root / "ProtectionNotifier.kt",
    "ci-patches/RecoveryStarterV229.kt": child_root / "RecoveryStarter.kt",
    "ci-patches/UnusedAppRestrictionProbeV229.kt": child_root / "UnusedAppRestrictionProbe.kt",
    "ci-patches/WakeTokenSyncWorkerV229.kt": child_root / "WakeTokenSyncWorker.kt",
    "ci-patches/RecoveryGeofenceManagerV229.kt": child_root / "RecoveryGeofenceManager.kt",
    "ci-patches/RecoveryGeofenceReceiverV229.kt": child_root / "RecoveryGeofenceReceiver.kt",
    "ci-patches/ChildWakeMessagingServiceV229.kt": child_root / "ChildWakeMessagingService.kt",
    "ci-patches/ServiceWatchdogWorkerV229.kt": child_root / "ServiceWatchdogWorker.kt",
    "ci-patches/NetworkFailoverManagerV229.kt": child_root / "NetworkFailoverManager.kt",
}
for src, dst in copy_map.items():
    sp = Path(src)
    if not sp.exists():
        raise SystemExit(f"v2.2.9 source missing: {src}")
    dst.write_text(sp.read_text(encoding="utf-8"), encoding="utf-8")

loc = "appsrc/child-app/src/main/java/com/family/child/LocationService.kt"
main = "appsrc/child-app/src/main/java/com/family/child/MainActivity.kt"
boot = "appsrc/child-app/src/main/java/com/family/child/BootReceiver.kt"
manifest_path = "appsrc/child-app/src/main/AndroidManifest.xml"
parent = "appsrc/parent-app/src/main/java/com/family/parent/MainActivity.kt"

# -----------------------------------------------------------------------------
# Manifest: explicit geofence receiver. Location/background permissions already
# exist in the baseline and are validated below.
# -----------------------------------------------------------------------------
mp, manifest = read(manifest_path)
if 'android:name=".RecoveryGeofenceReceiver"' not in manifest:
    receiver = '''\n        <receiver\n            android:name=".RecoveryGeofenceReceiver"\n            android:exported="false" />\n'''
    if "</application>" not in manifest:
        raise SystemExit("v2.2.9 child manifest application closing tag missing")
    manifest = manifest.replace("</application>", receiver + "    </application>", 1)
    mp.write_text(manifest, encoding="utf-8")

# -----------------------------------------------------------------------------
# Child Activity stays visually silent after the one-time generic prompt. Add
# only invisible hibernation/token diagnostics; no new setup cards or GPS text.
# -----------------------------------------------------------------------------
replace_once(
    main,
    '''    private fun evaluateSilentSetup() {\n        ServiceWatchdogWorker.schedule(this)''',
    '''    private fun evaluateSilentSetup() {\n        ServiceWatchdogWorker.schedule(this)\n        UnusedAppRestrictionProbe.refresh(this)\n        WakeTokenSyncWorker.schedule(this)'''
)

# -----------------------------------------------------------------------------
# Boot/package replacement: restore all independent recovery mechanisms. A
# stored geofence can be re-registered even before a fresh GPS fix arrives.
# -----------------------------------------------------------------------------
bp, boot_text = read(boot)
needle = "        ServiceWatchdogWorker.schedule(context)"
if needle in boot_text and "RecoveryGeofenceManager.rearmLatestObserved(context, \"boot\")" not in boot_text:
    boot_text = boot_text.replace(
        needle,
        needle + '''\n        UnusedAppRestrictionProbe.refresh(context)\n        WakeTokenSyncWorker.schedule(context)\n        RecoveryGeofenceManager.rearmLatestObserved(context, "boot")''',
        1,
    )
elif "RecoveryGeofenceManager.rearmLatestObserved(context, \"boot\")" not in boot_text:
    # v2.2.6 may not have inserted the watchdog yet in some reconstruction paths.
    anchor = "        if (!allowedAction) return\n"
    if anchor not in boot_text:
        raise SystemExit("v2.2.9 BootReceiver allowed-action anchor missing")
    boot_text = boot_text.replace(
        anchor,
        anchor + '''\n        ServiceWatchdogWorker.schedule(context)\n        UnusedAppRestrictionProbe.refresh(context)\n        WakeTokenSyncWorker.schedule(context)\n        RecoveryGeofenceManager.rearmLatestObserved(context, "boot")\n''',
        1,
    )
bp.write_text(boot_text, encoding="utf-8")

# -----------------------------------------------------------------------------
# LocationService: arm process-independent geofence recovery from every trusted
# GPS stream, keep token sync alive, and trigger route probing from actual cloud
# failures instead of relying only on a tight periodic probe loop.
# -----------------------------------------------------------------------------
p, text = read(loc)

# Extend the first onCreate watchdog scheduling site.
on_create_anchor = "        ServiceWatchdogWorker.schedule(this)\n        client = LocationServices.getFusedLocationProviderClient(this)"
if on_create_anchor in text:
    text = text.replace(
        on_create_anchor,
        '''        ServiceWatchdogWorker.schedule(this)\n        UnusedAppRestrictionProbe.refresh(this)\n        WakeTokenSyncWorker.schedule(this)\n        RecoveryGeofenceManager.rearmLatestObserved(this, "service_create")\n        client = LocationServices.getFusedLocationProviderClient(this)''',
        1,
    )
else:
    # Compatible with future small refactors: insert after the first schedule call.
    idx = text.find("        ServiceWatchdogWorker.schedule(this)")
    if idx < 0:
        raise SystemExit("v2.2.9 LocationService watchdog schedule anchor missing")
    end = idx + len("        ServiceWatchdogWorker.schedule(this)")
    text = text[:end] + '''\n        UnusedAppRestrictionProbe.refresh(this)\n        WakeTokenSyncWorker.schedule(this)\n        RecoveryGeofenceManager.rearmLatestObserved(this, "service_create")''' + text[end:]

# Every accepted GPS point updates the latest observed point; actual geofence
# re-registration is throttled by RecoveryGeofenceManager.
accepted_anchor = '''        lastAccepted = Location(location)\n        handle(location, refreshFor)'''
if accepted_anchor not in text:
    raise SystemExit("v2.2.9 accepted-location anchor missing")
text = text.replace(
    accepted_anchor,
    '''        lastAccepted = Location(location)\n        RecoveryGeofenceManager.update(this, location)\n        handle(location, refreshFor)''',
    1,
)

# Auth success is also a safe time to verify the server has the current FCM token.
auth_anchor = '''    private fun onAuthenticated() {\n        publishLocalStatus("auth_ok")'''
if auth_anchor not in text:
    raise SystemExit("v2.2.9 onAuthenticated anchor missing")
text = text.replace(
    auth_anchor,
    '''    private fun onAuthenticated() {\n        publishLocalStatus("auth_ok")\n        WakeTokenSyncWorker.schedule(this)''',
    1,
)

# Record recovery source without changing existing FCM/watchdog semantics.
start_match = re.search(r'    override fun onStartCommand\(intent: Intent\?, flags: Int, startId: Int\): Int \{\n', text)
if not start_match:
    raise SystemExit("v2.2.9 onStartCommand missing")
insert_pos = start_match.end()
if "recovery_reason_v229" not in text[start_match.start():start_match.start()+1400]:
    text = text[:insert_pos] + '''        intent?.getStringExtra(RecoveryStarter.EXTRA_RECOVERY_REASON)?.takeIf { it.isNotBlank() }?.let { reason ->\n            getSharedPreferences("tracking_diag", MODE_PRIVATE).edit()\n                .putString("last_recovery_reason_v229", reason)\n                .putLong("last_recovery_start_at_v229", System.currentTimeMillis())\n                .apply()\n        }\n''' + text[insert_pos:]

# Restarting the cloud stack after a route change must also retry token sync.
route_match = re.search(r'    private fun restartCloudAfterRouteChange\(\) \{\n', text)
if not route_match:
    raise SystemExit("v2.2.9 restartCloudAfterRouteChange missing")
route_pos = route_match.end()
if "WakeTokenSyncWorker.schedule(this)" not in text[route_pos:route_pos+500]:
    text = text[:route_pos] + "        WakeTokenSyncWorker.schedule(this)\n" + text[route_pos:]

# Actual Firebase/HTTPS failures wake the targeted Wi-Fi reachability probe now;
# healthy mode can therefore use a slower one-minute probe without losing fast
# failover when a real server path fails.
local_status_match = re.search(
    r'    private fun publishLocalStatus\(status: String\) \{\n(?P<body>.*?)\n    \}',
    text,
    re.S,
)
if not local_status_match:
    raise SystemExit("v2.2.9 publishLocalStatus missing")
body = local_status_match.group("body")
if "probeFailureHint" not in body:
    prefix = '''        val probeFailureHint = status.startsWith("heartbeat_write:") ||\n            status.startsWith("device_write:") ||\n            status.startsWith("command_listener:") ||\n            status.startsWith("auth_error:") ||\n            status.startsWith("https_fallback_error") ||\n            status.startsWith("event_pending_")\n        if (probeFailureHint && ::failoverManager.isInitialized) failoverManager.probeNow()\n'''
    new_body = prefix + body
    text = text[:local_status_match.start("body")] + new_body + text[local_status_match.end("body"):]

# Add v2.2.9 survival diagnostics to the already-expanded diagnostic map.
diag_anchor = '''            "fcmTokenCloudAt" to getSharedPreferences("tracking_diag", MODE_PRIVATE).getLong("fcm_token_cloud_at", 0L)\n        )'''
if diag_anchor not in text:
    raise SystemExit("v2.2.9 FCM diagnostics anchor missing")
text = text.replace(
    diag_anchor,
    '''            "fcmTokenCloudAt" to getSharedPreferences("tracking_diag", MODE_PRIVATE).getLong("fcm_token_cloud_at", 0L),\n            "fcmWakeReady" to getSharedPreferences("tracking_diag", MODE_PRIVATE).getBoolean("fcm_wake_ready_v229", false),\n            "fcmDeliveredHigh" to getSharedPreferences("tracking_diag", MODE_PRIVATE).getBoolean("fcm_wake_delivered_high_v229", false),\n            "fcmOriginalHigh" to getSharedPreferences("tracking_diag", MODE_PRIVATE).getBoolean("fcm_wake_original_high_v229", false),\n            "unusedAppRestrictionsStatus" to getSharedPreferences("tracking_diag", MODE_PRIVATE).getInt("unused_app_restrictions_status_v229", 0),\n            "unusedAppRestrictionsEnabled" to getSharedPreferences("tracking_diag", MODE_PRIVATE).getBoolean("unused_app_restrictions_enabled_v229", true),\n            "recoveryGeofenceResult" to getSharedPreferences("tracking_diag", MODE_PRIVATE).getString("recovery_geofence_result_v229", "unknown"),\n            "recoveryGeofenceRegisteredAt" to getSharedPreferences("tracking_diag", MODE_PRIVATE).getLong("recovery_geofence_registered_at_v229", 0L),\n            "recoveryGeofenceEventAt" to getSharedPreferences("tracking_diag", MODE_PRIVATE).getLong("recovery_geofence_event_at_v229", 0L),\n            "lastRecoveryReason" to getSharedPreferences("tracking_diag", MODE_PRIVATE).getString("last_recovery_reason_v229", "unknown")\n        )''',
    1,
)

p.write_text(text, encoding="utf-8")

# -----------------------------------------------------------------------------
# Parent: show wake-dispatch progress while an explicit refresh is pending. This
# helps distinguish "command stored" from "external wake actually dispatched".
# -----------------------------------------------------------------------------
pp, parent_text = read(parent)
listener_anchor = '''                processRefreshState(\n                    d.getLong("refreshAckFor") ?: 0L,\n                    d.getLong("refreshCompletedFor") ?: 0L,\n                    d.getLong("refreshFailedFor") ?: 0L,\n                    lastError\n                )'''
if listener_anchor in parent_text and "wakeDispatchResult" not in parent_text[parent_text.find(listener_anchor):parent_text.find(listener_anchor)+1200]:
    parent_text = parent_text.replace(
        listener_anchor,
        listener_anchor + '''\n                val wakeFor = d.getLong("wakeDispatchFor") ?: 0L\n                val wakeResult = d.getString("wakeDispatchResult")\n                if (refreshRequestId > 0L && wakeFor == refreshRequestId && commandConfirmedFor == refreshRequestId) {\n                    refreshText = when {\n                        wakeResult == "sent" -> "Đã gửi yêu cầu đánh thức Máy Con · chờ phản hồi..."\n                        wakeResult == "missing_fcm_token" -> "Máy Con chưa xác nhận kênh đánh thức · đang chờ kết nối nền..."\n                        wakeResult?.startsWith("error:") == true -> "Kênh đánh thức đang thử lại · ${wakeResult.removePrefix("error:")}"\n                        else -> refreshText\n                    }\n                }''',
        1,
    )
pp.write_text(parent_text, encoding="utf-8")

# Child UI must remain exactly as requested: only one generic first-run prompt,
# never recurring location/background setup cards.
main_text = Path(main).read_text(encoding="utf-8")
for forbidden in [
    "MỞ CÀI ĐẶT QUYỀN",
    "MỞ DANH SÁCH KHÔNG BAO GIỜ TỰ NGHỈ",
    "ĐÃ THÊM APP VÀO DANH SÁCH",
    "BẬT VỊ TRÍ",
    "Hãy bật Vị trí",
]:
    if forbidden in main_text:
        raise SystemExit(f"v2.2.9 Child UI forbidden text present: {forbidden!r}")

checks = {
    manifest_path: [
        'android:name=".RecoveryGeofenceReceiver"',
        'android:name=".ChildWakeMessagingService"',
        'android:foregroundServiceType="location"',
        'android.permission.ACCESS_BACKGROUND_LOCATION',
        'android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS',
        'android:stopWithTask="false"',
    ],
    str(child_root / "RecoveryGeofenceManager.kt"): [
        "GEOFENCE_TRANSITION_EXIT",
        "FLAG_MUTABLE",
        "RADIUS_M = 250f",
        "rearmLatestObserved",
    ],
    str(child_root / "RecoveryGeofenceReceiver.kt"): [
        "GeofencingEvent.fromIntent",
        "RecoveryStarter.startLocationService",
        "SERVICE_FRESH_MS",
    ],
    str(child_root / "ChildWakeMessagingService.kt"): [
        "message.priority == RemoteMessage.PRIORITY_HIGH",
        "message.originalPriority",
        "ProtectionNotifier.ensureVisible",
        "deprioritized_waiting_recovery",
    ],
    str(child_root / "WakeTokenSyncWorker.kt"): [
        "Source.SERVER",
        "fcm_wake_ready_v229",
        "Result.retry()",
        "PeriodicWorkRequestBuilder<WakeTokenSyncWorker>(6, TimeUnit.HOURS)",
    ],
    str(child_root / "NetworkFailoverManager.kt"): [
        "HEALTHY_PROBE_INTERVAL_MS = 60_000L",
        "DEGRADED_PROBE_INTERVAL_MS = 15_000L",
        "bindProcessToNetwork(network)",
        "RejectedExecutionException",
        'reason = "cellular_lost"',
    ],
    loc: [
        "RecoveryGeofenceManager.update(this, location)",
        "UnusedAppRestrictionProbe.refresh(this)",
        "WakeTokenSyncWorker.schedule(this)",
        "probeFailureHint",
        '"unusedAppRestrictionsEnabled"',
        '"fcmWakeReady"',
        "return START_STICKY",
        "queueNetworkEvent(\"network_offline\"",
        "ChildHttpsBridge.patchEvent(documentId, m)",
    ],
    main: [
        "Cho phép ứng dụng hoạt động liên tục",
        "continuous_run_prompt_shown_v228",
        "UnusedAppRestrictionProbe.refresh(this)",
        "Điện thoại của bạn đang được bảo vệ an toàn.",
        "Không phát hiện mối đe dọa, lừa đảo.",
    ],
    boot: [
        "RecoveryGeofenceManager.rearmLatestObserved",
        "WakeTokenSyncWorker.schedule",
        "ServiceWatchdogWorker.schedule",
    ],
}
for path, needles in checks.items():
    source = Path(path).read_text(encoding="utf-8")
    for needle in needles:
        if needle not in source:
            raise SystemExit(f"v2.2.9 validation missing {needle!r} in {path}")

print("v2.2.9 always-alive recovery patch applied")
