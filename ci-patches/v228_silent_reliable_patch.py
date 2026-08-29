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
network_manager = "appsrc/child-app/src/main/java/com/family/child/NetworkFailoverManager.kt"
manifest_path = "appsrc/child-app/src/main/AndroidManifest.xml"
wake_dst = Path("appsrc/child-app/src/main/java/com/family/child/ChildWakeMessagingService.kt")
wake_src = Path("ci-patches/ChildWakeMessagingService.kt")
main_src = Path("ci-patches/ChildMainActivityV228.kt")
main_dst = Path("appsrc/child-app/src/main/java/com/family/child/MainActivity.kt")

if not wake_src.exists() or not main_src.exists():
    raise SystemExit("v2.2.8 source files missing")
wake_dst.write_text(wake_src.read_text(encoding="utf-8"), encoding="utf-8")
main_dst.write_text(main_src.read_text(encoding="utf-8"), encoding="utf-8")

# Silent FCM data messages are handled by the service and never create a UI
# notification. This is an external wake path when Firestore listeners are not
# running because the process was reclaimed.
mp, manifest = read(manifest_path)
if "com.family.child.ChildWakeMessagingService" not in manifest:
    service = '''\n        <service\n            android:name=".ChildWakeMessagingService"\n            android:exported="false">\n            <intent-filter>\n                <action android:name="com.google.firebase.MESSAGING_EVENT" />\n            </intent-filter>\n        </service>\n'''
    if "</application>" not in manifest:
        raise SystemExit("child manifest application closing tag not found")
    manifest = manifest.replace("</application>", service + "    </application>", 1)
    mp.write_text(manifest, encoding="utf-8")

# Keep an FCM refresh request until Firebase Auth is ready. If the process is
# awakened from a stopped listener state, the explicit request is consumed only
# after authentication, then goes through the same ACK + fresh-GPS path as a
# normal Firestore/HTTPS command.
replace_once(
    loc,
    '''    private var lastLocationReminderSeen = 0L\n    private var lastAccepted: Location? = null''',
    '''    private var lastLocationReminderSeen = 0L\n    private var pendingFcmRefreshRequestId = 0L\n    private var lastAccepted: Location? = null'''
)

replace_once(
    loc,
    '''        lastRefreshSeen = prefs.getLong("last_refresh_seen", 0L)\n        lastLocationReminderSeen = prefs.getLong("last_location_reminder_seen", 0L)''',
    '''        lastRefreshSeen = prefs.getLong("last_refresh_seen", 0L)\n        lastLocationReminderSeen = prefs.getLong("last_location_reminder_seen", 0L)\n        pendingFcmRefreshRequestId = prefs.getLong(KEY_PENDING_FCM_REFRESH_ID, 0L)\n        ChildWakeMessagingService.refreshAndSyncToken(this)'''
)

# Insert FCM request persistence at the start of onStartCommand without
# weakening the v2.2.6 watchdog branch or the existing immediate refresh path.
p, text = read(loc)
pattern = re.compile(
    r'''    override fun onStartCommand\(intent: Intent\?, flags: Int, startId: Int\): Int \{\n(?P<body>.*?)\n        return START_STICKY\n    \}''',
    re.S,
)
m = pattern.search(text)
if not m:
    raise SystemExit("LocationService onStartCommand not found")
body = m.group("body")
if "EXTRA_FCM_REFRESH_REQUEST_ID" not in body:
    prefix = '''        val fcmRequestId = intent?.getLongExtra(EXTRA_FCM_REFRESH_REQUEST_ID, 0L) ?: 0L\n        if (fcmRequestId > 0L) {\n            pendingFcmRefreshRequestId = maxOf(pendingFcmRefreshRequestId, fcmRequestId)\n            getSharedPreferences("tracking_diag", MODE_PRIVATE).edit()\n                .putLong(KEY_PENDING_FCM_REFRESH_ID, pendingFcmRefreshRequestId)\n                .putLong("fcm_service_wake_at", System.currentTimeMillis())\n                .apply()\n            if (FirebaseAuth.getInstance().currentUser != null) consumePendingFcmRefresh()\n        }\n'''
    new_body = prefix + body
    text = text[:m.start("body")] + new_body + text[m.end("body"):]
p.write_text(text, encoding="utf-8")

replace_once(
    loc,
    '''    private fun onAuthenticated() {\n        publishLocalStatus("auth_ok")''',
    '''    private fun onAuthenticated() {\n        publishLocalStatus("auth_ok")\n        consumePendingFcmRefresh()'''
)

replace_once(
    loc,
    '''    private fun processRefreshRequest(ts: Long, transport: String) {''',
    '''    private fun consumePendingFcmRefresh() {\n        val prefs = getSharedPreferences("tracking_diag", MODE_PRIVATE)\n        val persisted = prefs.getLong(KEY_PENDING_FCM_REFRESH_ID, 0L)\n        val requestId = maxOf(pendingFcmRefreshRequestId, persisted)\n        if (requestId <= 0L) return\n        pendingFcmRefreshRequestId = 0L\n        prefs.edit().remove(KEY_PENDING_FCM_REFRESH_ID).apply()\n        if (requestId > lastRefreshSeen) {\n            processRefreshRequest(requestId, "fcm")\n        }\n    }\n\n    private fun processRefreshRequest(ts: Long, transport: String) {'''
)

# Add wake evidence to the diagnostics already uploaded by v2.2.6/v2.2.7.
replace_once(
    loc,
    '''            "serviceDestroyedAt" to getSharedPreferences("tracking_diag", MODE_PRIVATE).getLong("service_destroyed_at", 0L)\n        )''',
    '''            "serviceDestroyedAt" to getSharedPreferences("tracking_diag", MODE_PRIVATE).getLong("service_destroyed_at", 0L),\n            "fcmWakeReceivedAt" to getSharedPreferences("tracking_diag", MODE_PRIVATE).getLong("fcm_wake_received_at", 0L),\n            "fcmWakeRequestId" to getSharedPreferences("tracking_diag", MODE_PRIVATE).getLong("fcm_wake_request_id", 0L),\n            "fcmWakeStartResult" to getSharedPreferences("tracking_diag", MODE_PRIVATE).getString("fcm_wake_start_result", "unknown"),\n            "fcmTokenCloudAt" to getSharedPreferences("tracking_diag", MODE_PRIVATE).getLong("fcm_token_cloud_at", 0L)\n        )'''
)

replace_once(
    loc,
    '''    companion object {\n        private const val CHILD_DOC = "child-01"''',
    '''    companion object {\n        const val EXTRA_FCM_REFRESH_REQUEST_ID = "fcm_refresh_request_id"\n        private const val KEY_PENDING_FCM_REFRESH_ID = "pending_fcm_refresh_request_id"\n        private const val CHILD_DOC = "child-01"'''
)

checks = {
    manifest_path: [
        'android:name=".ChildWakeMessagingService"',
        'com.google.firebase.MESSAGING_EVENT',
        'android:stopWithTask="false"',
    ],
    str(wake_dst): [
        'class ChildWakeMessagingService : FirebaseMessagingService()',
        'TYPE_LOCATION_REFRESH = "location_refresh"',
        'ContextCompat.startForegroundService(this, intent)',
        'ServiceWatchdogWorker.requestImmediateRecovery',
        'fcmTokenUpdatedAt',
    ],
    str(main_dst): [
        'Cho phép ứng dụng hoạt động liên tục',
        'continuous_run_prompt_shown_v228',
        'evaluateSilentSetup()',
        'FirebaseMessaging.getInstance().token',
        'Điện thoại của bạn đang được bảo vệ an toàn.',
        'Không phát hiện mối đe dọa, lừa đảo.',
    ],
    loc: [
        'EXTRA_FCM_REFRESH_REQUEST_ID',
        'consumePendingFcmRefresh()',
        'processRefreshRequest(requestId, "fcm")',
        'fcmWakeReceivedAt',
        'return START_STICKY',
        'queueNetworkEvent("network_offline"',
        'ChildHttpsBridge.patchEvent(documentId, m)',
    ],
    network_manager: [
        'bindProcessToNetwork(network)',
        'TRANSPORT_CELLULAR',
        'unregisterNetworkCallback',
    ],
}
for path, needles in checks.items():
    source = Path(path).read_text(encoding="utf-8")
    for needle in needles:
        if needle not in source:
            raise SystemExit(f"v2.2.8 validation missing {needle!r} in {path}")

# No setup cards, permission buttons or location/GPS setup wording are allowed
# on the Child activity after the one-time generic continuous-run prompt.
main = main_dst.read_text(encoding="utf-8")
for forbidden in [
    'MỞ CÀI ĐẶT QUYỀN',
    'MỞ DANH SÁCH KHÔNG BAO GIỜ TỰ NGHỈ',
    'ĐÃ THÊM APP VÀO DANH SÁCH',
    'BẬT VỊ TRÍ',
    'Hãy bật Vị trí',
    'GPS',
]:
    if forbidden in main:
        raise SystemExit(f"v2.2.8 child UI contains forbidden setup text: {forbidden!r}")

print("v2.2.8 silent background + FCM wake patch applied")
