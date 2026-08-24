from pathlib import Path


def read(path):
    p = Path(path)
    if not p.exists():
        raise SystemExit(f"missing file: {p}")
    return p, p.read_text(encoding="utf-8")


def replace_once(path, old, new):
    p, text = read(path)
    if old not in text:
        raise SystemExit(f"anchor not found in {path}: {old[:140]!r}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


loc = "appsrc/child-app/src/main/java/com/family/child/LocationService.kt"
parent = "appsrc/parent-app/src/main/java/com/family/parent/MainActivity.kt"
parent_https = "appsrc/parent-app/src/main/java/com/family/parent/ParentHttpsBridge.kt"
child_manifest = "appsrc/child-app/src/main/AndroidManifest.xml"
network_manager = "appsrc/child-app/src/main/java/com/family/child/NetworkFailoverManager.kt"

# Android requires CHANGE_NETWORK_STATE for requestNetwork()/bindProcessToNetwork().
mp, manifest = read(child_manifest)
if "android.permission.CHANGE_NETWORK_STATE" not in manifest:
    pos = manifest.find(">")
    if pos < 0:
        raise SystemExit("child manifest opening tag not found")
    manifest = manifest[:pos + 1] + '\n    <uses-permission android:name="android.permission.CHANGE_NETWORK_STATE" />' + manifest[pos + 1:]
    mp.write_text(manifest, encoding="utf-8")

# The manager is kept as a normal Kotlin source file so network policy stays isolated.
src = Path("ci-patches/NetworkFailoverManager.kt")
if not src.exists():
    raise SystemExit("missing ci-patches/NetworkFailoverManager.kt")
Path(network_manager).write_text(src.read_text(encoding="utf-8"), encoding="utf-8")

replace_once(
    loc,
    '    private var serviceStartedAt = 0L\n    @Volatile private var pendingEventCount = 0',
    '    private var serviceStartedAt = 0L\n    private lateinit var failoverManager: NetworkFailoverManager\n    @Volatile private var pendingEventCount = 0'
)

replace_once(
    loc,
    '        state = StateStore(this)\n        state.restore(engine)',
    '''        state = StateStore(this)\n        failoverManager = NetworkFailoverManager(this) { reason ->\n            publishLocalStatus("network_route_$reason")\n            if (reason == "cellular_failover" || reason == "wifi_recovered" || reason == "cellular_lost") {\n                restartCloudAfterRouteChange()\n            }\n            publishFailoverState(reason)\n        }\n        state.restore(engine)'''
)

replace_once(
    loc,
    '        registerNetworkRecovery()\n        publishLocalStatus("starting")',
    '''        registerNetworkRecovery()\n        failoverManager.start()\n        publishLocalStatus("starting")'''
)

replace_once(
    loc,
    '''        override fun onAvailable(network: Network) {\n            handler.removeCallbacks(networkHistoryCheck)\n            handler.removeCallbacks(networkRecovery)\n            handler.postDelayed(networkRecovery, NETWORK_RECOVERY_DEBOUNCE_MS)\n        }''',
    '''        override fun onAvailable(network: Network) {\n            handler.removeCallbacks(networkHistoryCheck)\n            handler.removeCallbacks(networkRecovery)\n            handler.postDelayed(networkRecovery, NETWORK_RECOVERY_DEBOUNCE_MS)\n            failoverManager.probeNow()\n        }'''
)

replace_once(
    loc,
    '''    private fun diagnosticFields(now: Long): MutableMap<String, Any?> {\n        val network = networkState()\n        return mutableMapOf(''',
    '''    private fun diagnosticFields(now: Long): MutableMap<String, Any?> {\n        val network = networkState()\n        val failover = failoverManager.snapshot()\n        return mutableMapOf('''
)

replace_once(
    loc,
    '''            "syncUploadedCount" to getSharedPreferences("tracking_diag", MODE_PRIVATE).getInt(KEY_SYNC_UPLOADED_COUNT, 0)\n        )''',
    '''            "syncUploadedCount" to getSharedPreferences("tracking_diag", MODE_PRIVATE).getInt(KEY_SYNC_UPLOADED_COUNT, 0),\n            "routeMode" to failover.routeMode,\n            "cellularFailoverActive" to failover.cellularFailoverActive,\n            "cellularFailoverSince" to failover.cellularFailoverSince,\n            "wifiServerProbeOkAt" to failover.wifiServerProbeOkAt,\n            "wifiServerProbeFailAt" to failover.wifiServerProbeFailAt,\n            "wifiServerFailureCount" to failover.wifiServerFailureCount,\n            "cellularAvailable" to failover.cellularAvailable\n        )'''
)

replace_once(
    loc,
    '''    private fun trustedLocationTime(location: Location, now: Long): Long {''',
    '''    private fun restartCloudAfterRouteChange() {\n        commandListener?.remove()\n        commandListener = null\n        try {\n            cloud.disableNetwork().addOnCompleteListener {\n                cloud.enableNetwork().addOnCompleteListener {\n                    if (FirebaseAuth.getInstance().currentUser != null) {\n                        listenCommands()\n                        pollRestCommand()\n                        publishHeartbeat()\n                        flushPending()\n                        if (hasFineLocation() && isLocationEnabled()) requestImmediate(null)\n                    }\n                }\n            }\n        } catch (e: Exception) {\n            publishLocalStatus("network_route_restart:${e.javaClass.simpleName}")\n        }\n    }\n\n    private fun publishFailoverState(reason: String) {\n        if (FirebaseAuth.getInstance().currentUser == null) return\n        val s = failoverManager.snapshot()\n        val payload = mapOf<String, Any?>(\n            "routeMode" to s.routeMode,\n            "cellularFailoverActive" to s.cellularFailoverActive,\n            "cellularFailoverSince" to s.cellularFailoverSince,\n            "wifiServerProbeOkAt" to s.wifiServerProbeOkAt,\n            "wifiServerProbeFailAt" to s.wifiServerProbeFailAt,\n            "wifiServerFailureCount" to s.wifiServerFailureCount,\n            "cellularAvailable" to s.cellularAvailable,\n            "routeChangedAt" to System.currentTimeMillis(),\n            "routeReason" to reason\n        )\n        cloud.collection("devices").document(CHILD_DOC).set(payload, SetOptions.merge())\n            .addOnFailureListener { e -> publishLocalStatus("route_state:${e.javaClass.simpleName}") }\n        ChildHttpsBridge.patch(payload)\n    }\n\n    private fun trustedLocationTime(location: Location, now: Long): Long {'''
)

replace_once(
    loc,
    '''        handler.removeCallbacks(networkRecovery)\n        handler.removeCallbacks(networkHistoryCheck)\n        if (trackingActive)''',
    '''        handler.removeCallbacks(networkRecovery)\n        handler.removeCallbacks(networkHistoryCheck)\n        if (::failoverManager.isInitialized) failoverManager.stop()\n        if (trackingActive)'''
)

# Parent HTTPS fallback must carry route state even when realtime Firestore is the path that failed.
replace_once(
    parent_https,
    '''        val syncUploadedCount: Long = 0L,\n        val networkOfflineSince: Long = 0L,''',
    '''        val syncUploadedCount: Long = 0L,\n        val routeMode: String? = null,\n        val cellularFailoverActive: Boolean? = null,\n        val cellularFailoverSince: Long = 0L,\n        val wifiServerProbeOkAt: Long = 0L,\n        val wifiServerProbeFailAt: Long = 0L,\n        val wifiServerFailureCount: Long = 0L,\n        val cellularAvailable: Boolean? = null,\n        val networkOfflineSince: Long = 0L,'''
)

replace_once(
    parent_https,
    '''                                syncUploadedCount = long(fields, "syncUploadedCount"),\n                                networkOfflineSince = long(fields, "networkOfflineSince"),''',
    '''                                syncUploadedCount = long(fields, "syncUploadedCount"),\n                                routeMode = string(fields, "routeMode"),\n                                cellularFailoverActive = bool(fields, "cellularFailoverActive"),\n                                cellularFailoverSince = long(fields, "cellularFailoverSince"),\n                                wifiServerProbeOkAt = long(fields, "wifiServerProbeOkAt"),\n                                wifiServerProbeFailAt = long(fields, "wifiServerProbeFailAt"),\n                                wifiServerFailureCount = long(fields, "wifiServerFailureCount"),\n                                cellularAvailable = bool(fields, "cellularAvailable"),\n                                networkOfflineSince = long(fields, "networkOfflineSince"),'''
)

# Parent dashboard reads and explains the route selected by the Child.
replace_once(
    parent,
    '''    var childAppVersion by remember { mutableStateOf<String?>(null) }\n    var syncState by remember { mutableStateOf("unknown") }''',
    '''    var childAppVersion by remember { mutableStateOf<String?>(null) }\n    var routeMode by remember { mutableStateOf<String?>(null) }\n    var cellularFailoverActive by remember { mutableStateOf(false) }\n    var cellularFailoverSince by remember { mutableLongStateOf(0L) }\n    var wifiServerProbeFailAt by remember { mutableLongStateOf(0L) }\n    var wifiServerFailureCount by remember { mutableLongStateOf(0L) }\n    var syncState by remember { mutableStateOf("unknown") }'''
)

replace_once(
    parent,
    '''        childAppVersion = s.appVersion ?: childAppVersion\n        syncState = s.syncState ?: syncState''',
    '''        childAppVersion = s.appVersion ?: childAppVersion\n        routeMode = s.routeMode ?: routeMode\n        cellularFailoverActive = s.cellularFailoverActive ?: cellularFailoverActive\n        cellularFailoverSince = s.cellularFailoverSince\n        if (s.wifiServerProbeFailAt > 0L) wifiServerProbeFailAt = s.wifiServerProbeFailAt\n        wifiServerFailureCount = s.wifiServerFailureCount\n        syncState = s.syncState ?: syncState'''
)

replace_once(
    parent,
    '''                childAppVersion = d.getString("appVersion") ?: childAppVersion\n                syncState = d.getString("syncState") ?: syncState''',
    '''                childAppVersion = d.getString("appVersion") ?: childAppVersion\n                routeMode = d.getString("routeMode") ?: routeMode\n                cellularFailoverActive = d.getBoolean("cellularFailoverActive") ?: cellularFailoverActive\n                cellularFailoverSince = d.getLong("cellularFailoverSince") ?: cellularFailoverSince\n                wifiServerProbeFailAt = d.getLong("wifiServerProbeFailAt") ?: wifiServerProbeFailAt\n                wifiServerFailureCount = d.getLong("wifiServerFailureCount") ?: wifiServerFailureCount\n                syncState = d.getString("syncState") ?: syncState'''
)

replace_once(
    parent,
    '''    val assessment = assessConnection(\n        now = now,\n        seen = seen,\n        heartbeatAt = heartbeatAt,\n        diagnosticAt = diagnosticAt,\n        networkValidated = networkValidated,\n        networkAt = networkAt,\n        firebaseRealtimeOkAt = firebaseRealtimeOkAt,\n        firebaseWriteOkAt = firebaseWriteOkAt,\n        httpsFallbackOkAt = httpsFallbackOkAt,\n        serviceState = serviceState,\n        locationEnabled = locationEnabled\n    )''',
    '''    val baseAssessment = assessConnection(\n        now = now,\n        seen = seen,\n        heartbeatAt = heartbeatAt,\n        diagnosticAt = diagnosticAt,\n        networkValidated = networkValidated,\n        networkAt = networkAt,\n        firebaseRealtimeOkAt = firebaseRealtimeOkAt,\n        firebaseWriteOkAt = firebaseWriteOkAt,\n        httpsFallbackOkAt = httpsFallbackOkAt,\n        serviceState = serviceState,\n        locationEnabled = locationEnabled\n    )\n    val assessment = when {\n        cellularFailoverActive && baseAssessment.level != ConnectionLevel.LOST ->\n            baseAssessment.copy(\n                level = ConnectionLevel.CONNECTED,\n                title = "Đang kết nối",\n                detail = "Wi-Fi của Máy Con không liên lạc được máy chủ · đang dùng dữ liệu di động dự phòng."\n            )\n        (routeMode == "wifi_server_unreachable" || routeMode == "requesting_cellular") && baseAssessment.level != ConnectionLevel.LOST ->\n            baseAssessment.copy(\n                level = ConnectionLevel.WARNING,\n                title = "Đang chuyển đường truyền",\n                detail = "Wi-Fi có kết nối nhưng không tới được máy chủ · đang tìm mạng di động."\n            )\n        else -> baseAssessment\n    }'''
)

checks = {
    network_manager: [
        'bindProcessToNetwork(network)',
        'TRANSPORT_CELLULAR',
        'PROBE_URL = "https://firestore.googleapis.com/"',
        'WIFI_FAILURES_TO_FAILOVER = 3',
        'WIFI_RECOVERY_SUCCESSES = 3',
        'CELLULAR_MIN_HOLD_MS = 2 * 60_000L',
        'unregisterNetworkCallback',
    ],
    child_manifest: ['android.permission.CHANGE_NETWORK_STATE'],
    loc: [
        'NetworkFailoverManager(this)',
        'restartCloudAfterRouteChange',
        'cellularFailoverActive',
        'routeMode',
        'failoverManager.stop()',
        'queueNetworkEvent("network_offline"',
        'ChildHttpsBridge.patchEvent(documentId, m)',
        'notification_channel_disabled',
    ],
    parent_https: [
        'cellularFailoverActive: Boolean?',
        'routeMode: String?',
        'wifiServerFailureCount',
    ],
    parent: [
        'Wi-Fi của Máy Con không liên lạc được máy chủ · đang dùng dữ liệu di động dự phòng.',
        'Đang chuyển đường truyền',
        'cellularFailoverActive',
        'Đồng bộ nhật ký',
    ],
}
for path, needles in checks.items():
    text = Path(path).read_text(encoding="utf-8")
    for needle in needles:
        if needle not in text:
            raise SystemExit(f"v2.2.5 validation missing {needle!r} in {path}")

print("v2.2.5 automatic cellular failover patch applied")
