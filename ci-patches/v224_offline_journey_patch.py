from pathlib import Path


def read(path):
    p = Path(path)
    if not p.exists():
        raise SystemExit(f'missing file: {p}')
    return p, p.read_text(encoding='utf-8')


def replace_once(path, old, new):
    p, text = read(path)
    if old not in text:
        raise SystemExit(f'anchor not found in {path}: {old[:120]!r}')
    p.write_text(text.replace(old, new, 1), encoding='utf-8')


loc = 'appsrc/child-app/src/main/java/com/family/child/LocationService.kt'
child_https = 'appsrc/child-app/src/main/java/com/family/child/ChildHttpsBridge.kt'
parent = 'appsrc/parent-app/src/main/java/com/family/parent/MainActivity.kt'
parent_https = 'appsrc/parent-app/src/main/java/com/family/parent/ParentHttpsBridge.kt'

# -----------------------------------------------------------------------------
# CHILD: durable offline journey journal + deterministic recovery reporting.
# -----------------------------------------------------------------------------
replace_once(
    loc,
    '    private var serviceStartedAt = 0L\n    private val handler = Handler(Looper.getMainLooper())',
    '''    private var serviceStartedAt = 0L\n    @Volatile private var pendingEventCount = 0\n    private var syncSessionActive = false\n    private var syncSessionInitialPending = 0\n    private var syncSessionUploaded = 0\n    private var lastSyncProgressPublishedAt = 0L\n    private val handler = Handler(Looper.getMainLooper())'''
)

replace_once(
    loc,
    '''    private val networkRecovery = Runnable {\n        publishLocalStatus("network_recovery")\n        cloud.enableNetwork()''',
    '''    private val networkHistoryCheck = Runnable {\n        updateNetworkHistory(System.currentTimeMillis())\n    }\n\n    private val networkRecovery = Runnable {\n        val now = System.currentTimeMillis()\n        updateNetworkHistory(now)\n        if (!networkState().second) {\n            publishLocalStatus("network_recovery_waiting_validated")\n            return@Runnable\n        }\n        publishLocalStatus("network_recovery")\n        cloud.enableNetwork()'''
)
replace_once(
    loc,
    '''        override fun onAvailable(network: Network) {\n            handler.removeCallbacks(networkRecovery)\n            handler.postDelayed(networkRecovery, NETWORK_RECOVERY_DEBOUNCE_MS)\n        }\n\n        override fun onLost(network: Network) {\n            publishLocalStatus("network_lost")\n        }''',
    '''        override fun onAvailable(network: Network) {\n            handler.removeCallbacks(networkHistoryCheck)\n            handler.removeCallbacks(networkRecovery)\n            handler.postDelayed(networkRecovery, NETWORK_RECOVERY_DEBOUNCE_MS)\n        }\n\n        override fun onLost(network: Network) {\n            publishLocalStatus("network_lost")\n            handler.removeCallbacks(networkHistoryCheck)\n            handler.postDelayed(networkHistoryCheck, NETWORK_LOSS_CONFIRM_MS)\n        }'''
)
replace_once(
    loc,
    '''        state.restore(engine)\n        val prefs = getSharedPreferences("tracking_diag", MODE_PRIVATE)''',
    '''        state.restore(engine)\n        io.execute {\n            pendingEventCount = try { local.batch(PENDING_REPORT_LIMIT).size } catch (_: Exception) { 0 }\n        }\n        val prefs = getSharedPreferences("tracking_diag", MODE_PRIVATE)'''
)
replace_once(
    loc,
    '''    private fun handle(l: Location, refreshFor: Long?) {\n        val now = System.currentTimeMillis()\n        val sample = Sample(\n            GeoPoint(l.latitude, l.longitude, l.accuracy.toDouble()),\n            now,''',
    '''    private fun handle(l: Location, refreshFor: Long?) {\n        val now = System.currentTimeMillis()\n        val sampleTime = trustedLocationTime(l, now)\n        val sample = Sample(\n            GeoPoint(l.latitude, l.longitude, l.accuracy.toDouble()),\n            sampleTime,'''
)
replace_once(
    loc,
    '''        maybeQueueLocationSample(l, now)\n        flushPending()''',
    '''        maybeQueueLocationSample(l, sampleTime)\n        flushPending()'''
)
replace_once(
    loc,
    '''        io.execute { local.insert(o.toString()); flushPending() }\n    }\n\n    private fun queueEvent(e: Event) {''',
    '''        io.execute {\n            local.insert(o.toString())\n            pendingEventCount = (pendingEventCount + 1).coerceAtMost(PENDING_REPORT_LIMIT)\n            flushPending()\n        }\n    }\n\n    private fun queueEvent(e: Event) {'''
)
replace_once(
    loc,
    '''        io.execute { local.insert(o.toString()); flushPending() }\n    }\n\n    private fun flushPending() {''',
    '''        io.execute {\n            local.insert(o.toString())\n            pendingEventCount = (pendingEventCount + 1).coerceAtMost(PENDING_REPORT_LIMIT)\n            flushPending()\n        }\n    }\n\n    private fun queueNetworkEvent(type: String, eventTime: Long, offlineSince: Long = 0L, durationMs: Long = 0L) {\n        val o = JSONObject()\n            .put("type", type)\n            .put("id", "$type-$eventTime")\n            .put("time", eventTime)\n        if (offlineSince > 0L) o.put("offlineSince", offlineSince)\n        if (durationMs > 0L) o.put("durationMs", durationMs)\n        io.execute {\n            local.insert(o.toString())\n            pendingEventCount = (pendingEventCount + 1).coerceAtMost(PENDING_REPORT_LIMIT)\n            flushPending()\n        }\n    }\n\n    private fun flushPending() {'''
)
replace_once(
    loc,
    '''    private fun flushPending() {\n        if (FirebaseAuth.getInstance().currentUser == null || !flushing.compareAndSet(false, true)) return\n        io.execute { uploadNext() }\n    }\n\n    private fun uploadNext() {\n        val p = local.batch(1).firstOrNull()\n        if (p == null) { flushing.set(false); return }\n        val o = JSONObject(p.json)\n        val m = mutableMapOf<String, Any?>()\n        o.keys().forEach { k -> m[k] = if (o.isNull(k)) null else o.get(k) }\n        cloud.collection("devices").document(CHILD_DOC).collection("events")\n            .document("${m["type"]}-${m["id"]}-${m["time"]}").set(m)\n            .addOnSuccessListener { io.execute { local.delete(p.localId); uploadNext() } }\n            .addOnFailureListener { e -> publishLocalStatus("event_write:${e.javaClass.simpleName}"); flushing.set(false) }\n    }''',
    '''    private fun flushPending() {\n        if (FirebaseAuth.getInstance().currentUser == null || !flushing.compareAndSet(false, true)) return\n        io.execute {\n            pendingEventCount = try { local.batch(PENDING_REPORT_LIMIT).size } catch (_: Exception) { pendingEventCount }\n            if (pendingEventCount > 0 && !syncSessionActive) {\n                syncSessionActive = true\n                syncSessionInitialPending = pendingEventCount\n                syncSessionUploaded = 0\n                publishSyncState("syncing")\n            }\n            uploadNext()\n        }\n    }\n\n    private fun uploadNext() {\n        val p = local.batch(1).firstOrNull()\n        if (p == null) {\n            pendingEventCount = 0\n            flushing.set(false)\n            if (syncSessionActive) {\n                syncSessionActive = false\n                publishSyncState("complete")\n            }\n            return\n        }\n        val o = try { JSONObject(p.json) } catch (e: Exception) {\n            publishLocalStatus("event_json:${e.javaClass.simpleName}")\n            flushing.set(false)\n            publishSyncState("pending")\n            return\n        }\n        val m = mutableMapOf<String, Any?>()\n        o.keys().forEach { k -> m[k] = if (o.isNull(k)) null else o.get(k) }\n        val documentId = "${m["type"]}-${m["id"]}-${m["time"]}"\n        val resolved = AtomicBoolean(false)\n\n        fun uploaded(transport: String) {\n            if (!resolved.compareAndSet(false, true)) return\n            io.execute {\n                local.delete(p.localId)\n                pendingEventCount = (pendingEventCount - 1).coerceAtLeast(0)\n                syncSessionUploaded++\n                val progressNow = System.currentTimeMillis()\n                if (progressNow - lastSyncProgressPublishedAt >= SYNC_PROGRESS_PUBLISH_MS) {\n                    lastSyncProgressPublishedAt = progressNow\n                    publishSyncState("syncing")\n                }\n                publishLocalStatus("event_sent_$transport")\n                uploadNext()\n            }\n        }\n\n        fun fallback(reason: String) {\n            if (resolved.get()) return\n            ChildHttpsBridge.patchEvent(documentId, m) { ok, error ->\n                if (ok) uploaded("https")\n                else if (resolved.compareAndSet(false, true)) {\n                    publishLocalStatus("event_pending_${reason}:${error ?: "unknown"}")\n                    flushing.set(false)\n                    publishSyncState("pending")\n                }\n            }\n        }\n\n        cloud.collection("devices").document(CHILD_DOC).collection("events")\n            .document(documentId).set(m)\n            .addOnSuccessListener { uploaded("firebase") }\n            .addOnFailureListener { e -> fallback(e.javaClass.simpleName) }\n\n        handler.postDelayed({ fallback("timeout") }, EVENT_FIRESTORE_TIMEOUT_MS)\n    }'''
)
replace_once(
    loc,
    '''    private fun publishHeartbeat() {\n        val now = System.currentTimeMillis()\n        maintainTrackingNotification(now)''',
    '''    private fun publishHeartbeat() {\n        val now = System.currentTimeMillis()\n        maintainTrackingNotification(now)\n        updateNetworkHistory(now)'''
)
replace_once(
    loc,
    '''            "appVersion" to BuildConfig.VERSION_NAME,\n            "httpsLatencyMs" to ChildHttpsBridge.lastLatencyMs.coerceAtLeast(0L)''',
    '''            "appVersion" to BuildConfig.VERSION_NAME,\n            "httpsLatencyMs" to ChildHttpsBridge.lastLatencyMs.coerceAtLeast(0L),\n            "pendingEventCount" to pendingEventCount,\n            "syncState" to if (syncSessionActive) "syncing" else if (pendingEventCount > 0) "pending" else "complete",\n            "networkOfflineSince" to getSharedPreferences("tracking_diag", MODE_PRIVATE).getLong(KEY_NETWORK_OFFLINE_SINCE, 0L),\n            "lastOfflineStartAt" to getSharedPreferences("tracking_diag", MODE_PRIVATE).getLong(KEY_LAST_OFFLINE_START, 0L),\n            "lastOfflineEndAt" to getSharedPreferences("tracking_diag", MODE_PRIVATE).getLong(KEY_LAST_OFFLINE_END, 0L),\n            "lastOfflineDurationMs" to getSharedPreferences("tracking_diag", MODE_PRIVATE).getLong(KEY_LAST_OFFLINE_DURATION, 0L),\n            "syncCompletedAt" to getSharedPreferences("tracking_diag", MODE_PRIVATE).getLong(KEY_SYNC_COMPLETED_AT, 0L),\n            "syncUploadedCount" to getSharedPreferences("tracking_diag", MODE_PRIVATE).getInt(KEY_SYNC_UPLOADED_COUNT, 0)'''
)
replace_once(
    loc,
    '''    private fun networkState(): Pair<String, Boolean> {''',
    '''    private fun trustedLocationTime(location: Location, now: Long): Long {\n        val t = location.time\n        return if (t > 0L && t <= now + 60_000L && now - t <= TRUSTED_LOCATION_TIME_MAX_AGE_MS) t else now\n    }\n\n    private fun updateNetworkHistory(now: Long) {\n        val prefs = getSharedPreferences("tracking_diag", MODE_PRIVATE)\n        val validated = networkState().second\n        val offlineSince = prefs.getLong(KEY_NETWORK_OFFLINE_SINCE, 0L)\n        if (!validated) {\n            if (offlineSince <= 0L) {\n                prefs.edit().putLong(KEY_NETWORK_OFFLINE_SINCE, now).apply()\n                queueNetworkEvent("network_offline", now)\n                publishLocalStatus("network_offline_recorded")\n            }\n            return\n        }\n        if (offlineSince > 0L && offlineSince <= now) {\n            val duration = now - offlineSince\n            prefs.edit()\n                .remove(KEY_NETWORK_OFFLINE_SINCE)\n                .putLong(KEY_LAST_OFFLINE_START, offlineSince)\n                .putLong(KEY_LAST_OFFLINE_END, now)\n                .putLong(KEY_LAST_OFFLINE_DURATION, duration)\n                .apply()\n            queueNetworkEvent("network_restored", now, offlineSince, duration)\n            publishLocalStatus("network_restored_recorded")\n        }\n    }\n\n    private fun publishSyncState(stateName: String) {\n        val now = System.currentTimeMillis()\n        val prefs = getSharedPreferences("tracking_diag", MODE_PRIVATE)\n        if (stateName == "complete") {\n            prefs.edit()\n                .putLong(KEY_SYNC_COMPLETED_AT, now)\n                .putInt(KEY_SYNC_UPLOADED_COUNT, syncSessionUploaded)\n                .apply()\n        }\n        val payload = mapOf<String, Any?>(\n            "syncState" to stateName,\n            "syncPendingCount" to pendingEventCount,\n            "syncStartedAt" to if (syncSessionActive) now else 0L,\n            "syncCompletedAt" to prefs.getLong(KEY_SYNC_COMPLETED_AT, 0L),\n            "syncUploadedCount" to syncSessionUploaded,\n            "lastOfflineStartAt" to prefs.getLong(KEY_LAST_OFFLINE_START, 0L),\n            "lastOfflineEndAt" to prefs.getLong(KEY_LAST_OFFLINE_END, 0L),\n            "lastOfflineDurationMs" to prefs.getLong(KEY_LAST_OFFLINE_DURATION, 0L)\n        )\n        if (FirebaseAuth.getInstance().currentUser != null) {\n            cloud.collection("devices").document(CHILD_DOC).set(payload, SetOptions.merge())\n                .addOnFailureListener { e -> publishLocalStatus("sync_state:${e.javaClass.simpleName}") }\n            ChildHttpsBridge.patch(payload)\n        }\n    }\n\n    private fun networkState(): Pair<String, Boolean> {'''
)
replace_once(
    loc,
    '''        handler.removeCallbacks(networkRecovery)\n        if (trackingActive)''',
    '''        handler.removeCallbacks(networkRecovery)\n        handler.removeCallbacks(networkHistoryCheck)\n        if (trackingActive)'''
)
replace_once(
    loc,
    '''        private const val NETWORK_RECOVERY_DEBOUNCE_MS = 1_500L\n        private const val FIREBASE_HEALTH_THROTTLE_MS = 60_000L''',
    '''        private const val NETWORK_RECOVERY_DEBOUNCE_MS = 1_500L\n        private const val NETWORK_LOSS_CONFIRM_MS = 2_500L\n        private const val EVENT_FIRESTORE_TIMEOUT_MS = 12_000L\n        private const val SYNC_PROGRESS_PUBLISH_MS = 10_000L\n        private const val PENDING_REPORT_LIMIT = 5_000\n        private const val TRUSTED_LOCATION_TIME_MAX_AGE_MS = 10 * 60_000L\n        private const val KEY_NETWORK_OFFLINE_SINCE = "network_offline_since"\n        private const val KEY_LAST_OFFLINE_START = "last_offline_start"\n        private const val KEY_LAST_OFFLINE_END = "last_offline_end"\n        private const val KEY_LAST_OFFLINE_DURATION = "last_offline_duration"\n        private const val KEY_SYNC_COMPLETED_AT = "sync_completed_at"\n        private const val KEY_SYNC_UPLOADED_COUNT = "sync_uploaded_count"\n        private const val FIREBASE_HEALTH_THROTTLE_MS = 60_000L'''
)

# CHILD HTTPS: event-document fallback.
replace_once(
    child_https,
    '''    fun readDevice(callback: (DeviceState?, String?) -> Unit) {''',
    '''    fun patchEvent(documentId: String, fields: Map<String, Any?>, callback: (Boolean, String?) -> Unit) {\n        withToken(\n            onToken = { token ->\n                executor.execute {\n                    try {\n                        val safeId = java.net.URLEncoder.encode(documentId, "UTF-8")\n                        val mask = fields.keys.joinToString("&") { "updateMask.fieldPaths=" + java.net.URLEncoder.encode(it, "UTF-8") }\n                        val url = "$DOC_URL/events/$safeId?$mask"\n                        val connection = (URL(url).openConnection() as HttpURLConnection).apply {\n                            requestMethod = "PATCH"\n                            connectTimeout = 8_000\n                            readTimeout = 8_000\n                            doOutput = true\n                            setRequestProperty("Authorization", "Bearer $token")\n                            setRequestProperty("Content-Type", "application/json; charset=UTF-8")\n                        }\n                        val jsonFields = JSONObject()\n                        fields.forEach { (key, value) -> jsonFields.put(key, encodeValue(value)) }\n                        val body = JSONObject().put("fields", jsonFields).toString()\n                        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }\n                        val code = connection.responseCode\n                        val message = if (code in 200..299) null else readError(connection)\n                        connection.disconnect()\n                        post { callback(code in 200..299, message ?: if (code in 200..299) null else "HTTP $code") }\n                    } catch (e: Exception) {\n                        post { callback(false, e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")) }\n                    }\n                }\n            },\n            onError = { post { callback(false, it) } }\n        )\n    }\n\n    fun readDevice(callback: (DeviceState?, String?) -> Unit) {'''
)

# PARENT HTTPS: day-range journal fallback and sync metadata.
replace_once(
    parent_https,
    '''    data class DeviceState(\n        val lastLat: Double? = null,''',
    '''    data class RemoteEvent(\n        val type: String = "",\n        val time: Long = 0L,\n        val lat: Double? = null,\n        val lon: Double? = null,\n        val accuracy: Double? = null,\n        val id: String = "",\n        val offlineSince: Long = 0L,\n        val durationMs: Long = 0L\n    )\n\n    data class DeviceState(\n        val lastLat: Double? = null,'''
)
replace_once(
    parent_https,
    '''        val appVersion: String? = null,\n        val refreshRequestedAt: Long = 0L,''',
    '''        val appVersion: String? = null,\n        val syncState: String? = null,\n        val syncPendingCount: Long = 0L,\n        val syncCompletedAt: Long = 0L,\n        val syncUploadedCount: Long = 0L,\n        val networkOfflineSince: Long = 0L,\n        val lastOfflineStartAt: Long = 0L,\n        val lastOfflineEndAt: Long = 0L,\n        val lastOfflineDurationMs: Long = 0L,\n        val refreshRequestedAt: Long = 0L,'''
)
replace_once(
    parent_https,
    '''                                appVersion = string(fields, "appVersion"),\n                                refreshRequestedAt = long(fields, "refreshRequestedAt"),''',
    '''                                appVersion = string(fields, "appVersion"),\n                                syncState = string(fields, "syncState"),\n                                syncPendingCount = long(fields, "syncPendingCount"),\n                                syncCompletedAt = long(fields, "syncCompletedAt"),\n                                syncUploadedCount = long(fields, "syncUploadedCount"),\n                                networkOfflineSince = long(fields, "networkOfflineSince"),\n                                lastOfflineStartAt = long(fields, "lastOfflineStartAt"),\n                                lastOfflineEndAt = long(fields, "lastOfflineEndAt"),\n                                lastOfflineDurationMs = long(fields, "lastOfflineDurationMs"),\n                                refreshRequestedAt = long(fields, "refreshRequestedAt"),'''
)
replace_once(
    parent_https,
    '''    private fun withToken(onToken: (String) -> Unit, onError: (String) -> Unit) {''',
    '''    fun readEvents(dayStart: Long, dayEnd: Long, callback: (List<RemoteEvent>?, String?) -> Unit) {\n        withToken(\n            onToken = { token ->\n                executor.execute {\n                    try {\n                        val url = "https://firestore.googleapis.com/v1/projects/family-location-884e5/databases/(default)/documents/devices/child-01:runQuery"\n                        val query = JSONObject().put("structuredQuery", JSONObject()\n                            .put("from", org.json.JSONArray().put(JSONObject().put("collectionId", "events")))\n                            .put("where", JSONObject().put("compositeFilter", JSONObject()\n                                .put("op", "AND")\n                                .put("filters", org.json.JSONArray()\n                                    .put(JSONObject().put("fieldFilter", JSONObject()\n                                        .put("field", JSONObject().put("fieldPath", "time"))\n                                        .put("op", "GREATER_THAN_OR_EQUAL")\n                                        .put("value", JSONObject().put("integerValue", dayStart.toString()))))\n                                    .put(JSONObject().put("fieldFilter", JSONObject()\n                                        .put("field", JSONObject().put("fieldPath", "time"))\n                                        .put("op", "LESS_THAN")\n                                        .put("value", JSONObject().put("integerValue", dayEnd.toString())))))))\n                            .put("orderBy", org.json.JSONArray().put(JSONObject()\n                                .put("field", JSONObject().put("fieldPath", "time"))\n                                .put("direction", "ASCENDING")))\n                            .put("limit", 5000))\n                        val connection = (URL(url).openConnection() as HttpURLConnection).apply {\n                            requestMethod = "POST"\n                            connectTimeout = 8_000\n                            readTimeout = 12_000\n                            doOutput = true\n                            setRequestProperty("Authorization", "Bearer $token")\n                            setRequestProperty("Content-Type", "application/json; charset=UTF-8")\n                            setRequestProperty("Accept", "application/json")\n                        }\n                        connection.outputStream.use { it.write(query.toString().toByteArray(Charsets.UTF_8)) }\n                        val code = connection.responseCode\n                        if (code in 200..299) {\n                            val body = connection.inputStream.bufferedReader().use { it.readText() }\n                            val rows = org.json.JSONArray(body)\n                            val result = mutableListOf<RemoteEvent>()\n                            for (i in 0 until rows.length()) {\n                                val doc = rows.optJSONObject(i)?.optJSONObject("document") ?: continue\n                                val fields = doc.optJSONObject("fields") ?: continue\n                                result += RemoteEvent(\n                                    type = string(fields, "type") ?: "",\n                                    time = long(fields, "time"),\n                                    lat = number(fields, "lat"),\n                                    lon = number(fields, "lon"),\n                                    accuracy = number(fields, "accuracy"),\n                                    id = string(fields, "id") ?: "",\n                                    offlineSince = long(fields, "offlineSince"),\n                                    durationMs = long(fields, "durationMs")\n                                )\n                            }\n                            connection.disconnect()\n                            post { callback(result, null) }\n                        } else {\n                            val error = readError(connection)\n                            connection.disconnect()\n                            post { callback(null, error ?: "HTTP $code") }\n                        }\n                    } catch (e: Exception) {\n                        post { callback(null, e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")) }\n                    }\n                }\n            },\n            onError = { post { callback(null, it) } }\n        )\n    }\n\n    private fun withToken(onToken: (String) -> Unit, onError: (String) -> Unit) {'''
)

# PARENT UI/state: merge Firebase + HTTPS history and show sync truth.
replace_once(
    parent,
    '''data class UiEvent(\n    val type: String = "",\n    val time: Long = 0L,\n    val lat: Double? = null,\n    val lon: Double? = null,\n    val accuracy: Double? = null,\n    val id: String = ""\n)''',
    '''data class UiEvent(\n    val type: String = "",\n    val time: Long = 0L,\n    val lat: Double? = null,\n    val lon: Double? = null,\n    val accuracy: Double? = null,\n    val id: String = "",\n    val offlineSince: Long = 0L,\n    val durationMs: Long = 0L\n)'''
)
replace_once(
    parent,
    '''    var childAppVersion by remember { mutableStateOf<String?>(null) }\n\n    var events by remember''',
    '''    var childAppVersion by remember { mutableStateOf<String?>(null) }\n    var syncState by remember { mutableStateOf("unknown") }\n    var syncPendingCount by remember { mutableLongStateOf(0L) }\n    var syncCompletedAt by remember { mutableLongStateOf(0L) }\n    var syncUploadedCount by remember { mutableLongStateOf(0L) }\n    var networkOfflineSince by remember { mutableLongStateOf(0L) }\n    var lastOfflineStartAt by remember { mutableLongStateOf(0L) }\n    var lastOfflineEndAt by remember { mutableLongStateOf(0L) }\n    var lastOfflineDurationMs by remember { mutableLongStateOf(0L) }\n\n    var events by remember'''
)
replace_once(
    parent,
    '''    var lastHttpsSuccessAt by remember { mutableLongStateOf(0L) }\n    var httpsFallbackActive by remember { mutableStateOf(false) }''',
    '''    var lastHttpsSuccessAt by remember { mutableLongStateOf(0L) }\n    var lastEventServerSnapshotAt by remember { mutableLongStateOf(0L) }\n    var lastHttpsEventsFetchAt by remember { mutableLongStateOf(0L) }\n    var httpsFallbackActive by remember { mutableStateOf(false) }'''
)
replace_once(
    parent,
    '''        childAppVersion = s.appVersion ?: childAppVersion\n        processRefreshState''',
    '''        childAppVersion = s.appVersion ?: childAppVersion\n        syncState = s.syncState ?: syncState\n        syncPendingCount = s.syncPendingCount\n        if (s.syncCompletedAt > 0L) syncCompletedAt = s.syncCompletedAt\n        syncUploadedCount = s.syncUploadedCount\n        networkOfflineSince = s.networkOfflineSince\n        if (s.lastOfflineStartAt > 0L) lastOfflineStartAt = s.lastOfflineStartAt\n        if (s.lastOfflineEndAt > 0L) lastOfflineEndAt = s.lastOfflineEndAt\n        if (s.lastOfflineDurationMs > 0L) lastOfflineDurationMs = s.lastOfflineDurationMs\n        processRefreshState'''
)
replace_once(
    parent,
    '''                childAppVersion = d.getString("appVersion") ?: childAppVersion\n                processRefreshState(''',
    '''                childAppVersion = d.getString("appVersion") ?: childAppVersion\n                syncState = d.getString("syncState") ?: syncState\n                syncPendingCount = d.getLong("syncPendingCount") ?: syncPendingCount\n                syncCompletedAt = d.getLong("syncCompletedAt") ?: syncCompletedAt\n                syncUploadedCount = d.getLong("syncUploadedCount") ?: syncUploadedCount\n                networkOfflineSince = d.getLong("networkOfflineSince") ?: networkOfflineSince\n                lastOfflineStartAt = d.getLong("lastOfflineStartAt") ?: lastOfflineStartAt\n                lastOfflineEndAt = d.getLong("lastOfflineEndAt") ?: lastOfflineEndAt\n                lastOfflineDurationMs = d.getLong("lastOfflineDurationMs") ?: lastOfflineDurationMs\n                processRefreshState('''
)
replace_once(
    parent,
    '''                else if (q != null) {\n                    eventFromCache = q.metadata.isFromCache\n                    events = q.documents.mapNotNull { it.toObject(UiEvent::class.java) }\n                }''',
    '''                else if (q != null) {\n                    eventFromCache = q.metadata.isFromCache\n                    if (!q.metadata.isFromCache) lastEventServerSnapshotAt = System.currentTimeMillis()\n                    val incoming = q.documents.mapNotNull { it.toObject(UiEvent::class.java) }\n                    events = mergeUiEvents(events, incoming)\n                }'''
)
replace_once(
    parent,
    '''            if (deviceFromCache || serverStale || refreshRequestId > 0L || reminderRequestId > 0L) {\n                ParentHttpsBridge.readDevice { state, _ ->\n                    if (state != null) {\n                        lastHttpsSuccessAt = System.currentTimeMillis()\n                        if (deviceFromCache || serverStale) httpsFallbackActive = true\n                        applyRestState(state)\n                    }\n                }\n            }''',
    '''            if (deviceFromCache || serverStale || refreshRequestId > 0L || reminderRequestId > 0L) {\n                ParentHttpsBridge.readDevice { state, _ ->\n                    if (state != null) {\n                        lastHttpsSuccessAt = System.currentTimeMillis()\n                        if (deviceFromCache || serverStale) httpsFallbackActive = true\n                        applyRestState(state)\n                    }\n                }\n            }\n            val eventStale = eventFromCache || lastEventServerSnapshotAt == 0L || tick - lastEventServerSnapshotAt > EVENT_SERVER_STALE_MS\n            if (eventStale && tick - lastHttpsEventsFetchAt > EVENT_HTTPS_FETCH_MIN_INTERVAL_MS) {\n                lastHttpsEventsFetchAt = tick\n                val fetchStart = selectedDayStart\n                val fetchEnd = fetchStart + 24L * 60 * 60 * 1000\n                ParentHttpsBridge.readEvents(fetchStart, fetchEnd) { remote, _ ->\n                    if (remote != null) {\n                        val incoming = remote.map { UiEvent(it.type, it.time, it.lat, it.lon, it.accuracy, it.id, it.offlineSince, it.durationMs) }\n                        events = mergeUiEvents(events, incoming)\n                        httpsFallbackActive = true\n                    }\n                }\n            }'''
)
replace_once(
    parent,
    '''                summary = journeySummary,\n                lat = lat,''',
    '''                summary = journeySummary,\n                syncState = syncState,\n                syncPendingCount = syncPendingCount,\n                syncCompletedAt = syncCompletedAt,\n                syncUploadedCount = syncUploadedCount,\n                networkOfflineSince = networkOfflineSince,\n                lastOfflineStartAt = lastOfflineStartAt,\n                lastOfflineEndAt = lastOfflineEndAt,\n                lastOfflineDurationMs = lastOfflineDurationMs,\n                lat = lat,'''
)
replace_once(
    parent,
    '''    dayEvents: List<UiEvent>,\n    visits: List<UiVisit>,\n    summary: JourneySummary,\n    lat: Double?,''',
    '''    dayEvents: List<UiEvent>,\n    visits: List<UiVisit>,\n    summary: JourneySummary,\n    syncState: String,\n    syncPendingCount: Long,\n    syncCompletedAt: Long,\n    syncUploadedCount: Long,\n    networkOfflineSince: Long,\n    lastOfflineStartAt: Long,\n    lastOfflineEndAt: Long,\n    lastOfflineDurationMs: Long,\n    lat: Double?,'''
)
replace_once(
    parent,
    '''                    HealthRow("Thời gian dừng", formatDuration(summary.stoppedMs))\n                }\n            }\n        }\n\n        item {''',
    '''                    HealthRow("Thời gian dừng", formatDuration(summary.stoppedMs))\n                }\n            }\n        }\n\n        item {\n            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF101720)), modifier = Modifier.fillMaxWidth()) {\n                Column(Modifier.padding(16.dp)) {\n                    Text("Đồng bộ nhật ký", color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)\n                    Spacer(Modifier.height(8.dp))\n                    when {\n                        networkOfflineSince > 0L -> {\n                            Text("Máy Con đang không có Internet. GPS vẫn tiếp tục được ghi cục bộ nếu Vị trí và dịch vụ còn hoạt động.", color = Color(0xFFFFC857))\n                            Text("Mất mạng từ ${fmt(networkOfflineSince)}", color = Color(0xFFBFCED6), style = MaterialTheme.typography.bodySmall)\n                        }\n                        syncState == "syncing" || syncPendingCount > 0L -> {\n                            Text("Đang đồng bộ dữ liệu đã lưu trên Máy Con.", color = Color(0xFF55D8FF))\n                            Text("Còn khoảng $syncPendingCount bản ghi chưa xác nhận lên máy chủ.", color = Color(0xFFBFCED6), style = MaterialTheme.typography.bodySmall)\n                        }\n                        syncCompletedAt > 0L -> {\n                            Text("Nhật ký cục bộ đã đồng bộ xong.", color = Color(0xFF8EE6A8))\n                            Text("Lần gần nhất: ${fmt(syncCompletedAt)} · đã gửi bù $syncUploadedCount bản ghi.", color = Color(0xFFBFCED6), style = MaterialTheme.typography.bodySmall)\n                        }\n                        else -> Text("Chưa có phiên đồng bộ bù nào được ghi nhận.", color = Color(0xFFBFCED6), style = MaterialTheme.typography.bodySmall)\n                    }\n                    if (lastOfflineDurationMs > 0L && lastOfflineStartAt > 0L && lastOfflineEndAt > 0L) {\n                        Spacer(Modifier.height(8.dp))\n                        Text("Mất mạng gần nhất: ${fmt(lastOfflineStartAt)} – ${fmt(lastOfflineEndAt)} · ${formatDuration(lastOfflineDurationMs)}", color = Color(0xFF91A6B3), style = MaterialTheme.typography.bodySmall)\n                    }\n                }\n            }\n        }\n\n        item {'''
)
replace_once(
    parent,
    '''private fun calculateJourneySummary(events: List<UiEvent>, visits: List<UiVisit>, dayStart: Long, dayEnd: Long): JourneySummary {''',
    '''private fun mergeUiEvents(existing: List<UiEvent>, incoming: List<UiEvent>): List<UiEvent> {\n    if (incoming.isEmpty()) return existing\n    val merged = LinkedHashMap<String, UiEvent>()\n    (existing + incoming).forEach { e ->\n        val key = "${e.type}|${e.id}|${e.time}"\n        merged[key] = e\n    }\n    return merged.values.sortedBy { it.time }.takeLast(5000)\n}\n\nprivate fun calculateJourneySummary(events: List<UiEvent>, visits: List<UiVisit>, dayStart: Long, dayEnd: Long): JourneySummary {'''
)
replace_once(
    parent,
    '''private const val REMINDER_COOLDOWN_MS = 45_000L\nprivate const val LOCATION_REMINDER_DELAY_MS = 6 * 60 * 60_000L''',
    '''private const val REMINDER_COOLDOWN_MS = 45_000L\nprivate const val LOCATION_REMINDER_DELAY_MS = 6 * 60 * 60_000L\nprivate const val EVENT_SERVER_STALE_MS = 30_000L\nprivate const val EVENT_HTTPS_FETCH_MIN_INTERVAL_MS = 30_000L'''
)

checks = {
    loc: [
        'queueNetworkEvent("network_offline"',
        'queueNetworkEvent("network_restored"',
        'trustedLocationTime(l, now)',
        'ChildHttpsBridge.patchEvent(documentId, m)',
        'EVENT_FIRESTORE_TIMEOUT_MS = 12_000L',
        'syncPendingCount',
        'TRACKING_NOTIFICATION_RESHOW_MS = 8 * 60 * 60_000L',
        'notification_channel_disabled',
    ],
    child_https: ['fun patchEvent(documentId: String'],
    parent_https: ['fun readEvents(dayStart: Long, dayEnd: Long', 'syncPendingCount: Long'],
    parent: [
        'Đồng bộ nhật ký',
        'Máy Con đang không có Internet. GPS vẫn tiếp tục được ghi cục bộ',
        'mergeUiEvents',
        'ParentHttpsBridge.readEvents(fetchStart, fetchEnd)',
        'EVENT_HTTPS_FETCH_MIN_INTERVAL_MS = 30_000L',
        'LOCATION_REMINDER_DELAY_MS = 6 * 60 * 60_000L',
    ],
}
for path, needles in checks.items():
    text = Path(path).read_text(encoding='utf-8')
    for needle in needles:
        if needle not in text:
            raise SystemExit(f'v2.2.4 validation missing {needle!r} in {path}')

print('v2.2.4 offline journey reliability patch applied')
