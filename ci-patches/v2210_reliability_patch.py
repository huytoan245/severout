from pathlib import Path
import shutil

child = Path('appsrc/child-app/src/main/java/com/family/child')
for name in ['LocalDb', 'JournalCheckpoint', 'LocationPolicy', 'ChildWakeMessagingService', 'RecoveryGeofenceManager', 'RecoveryGeofenceReceiver', 'WakeTokenSyncWorker', 'NetworkFailoverManager', 'ChildHttpsBridge']:
    src = Path(f'ci-patches/{name}V2210.kt')
    if src.exists(): shutil.copyfile(src, child / f'{name}.kt')

def replace(path, old, new):
    p = Path(path)
    s = p.read_text(encoding='utf-8')
    if old not in s: raise SystemExit(f'v2210 missing anchor in {p}: {old[:100]}')
    p.write_text(s.replace(old, new, 1), encoding='utf-8')

core = Path('appsrc/core/src/main/kotlin/com/family/core/VisitEngine.kt')
replace(core, '    fun currentVisit(): Visit? = visit', '''    fun snapshot() = EngineSnapshot(visit?.copy(), trip?.copy(points = mutableListOf()), departureCandidateSince, arrivalCandidate, lastTripPersisted)
    fun restoreSnapshot(s: EngineSnapshot) {
        visit = s.visit?.copy(); trip = s.trip?.copy(points = mutableListOf())
        departureCandidateSince = s.departureCandidateSince; arrivalCandidate = s.arrivalCandidate; lastTripPersisted = s.lastTripPersisted
    }
    fun currentVisit(): Visit? = visit''')
replace(core, '                    departureCandidateSince = null\n                }', '                    out += Event.TripPointAdded(t.copy(points = t.points.toMutableList()), sample)\n                    departureCandidateSince = null\n                }')
with core.open('a', encoding='utf-8') as f: f.write('\ndata class EngineSnapshot(val visit: Visit?, val trip: Trip?, val departureCandidateSince: Long?, val arrivalCandidate: Sample?, val lastTripPersisted: Sample?)\n')

loc = child / 'LocationService.kt'
replace(loc, 'import com.google.android.gms.location.*', 'import com.google.android.gms.location.*\nimport com.google.android.gms.tasks.CancellationTokenSource\nimport java.util.concurrent.RejectedExecutionException')
replace(loc, '    private var trackingActive = false', '''    @Volatile private var destroyed = false
    private var authInFlight = false
    private var authRetryAt = 0L
    private var listenerRetryAt = 0L
    private var lastAcceptedGpsTime = 0L
    private var activeRefreshId = 0L
    private var acquiring = false
    private var acquisitionToken: CancellationTokenSource? = null
    @Volatile private var lastCloudHeartbeatSuccessAt = 0L
    private var trackingActive = false
    private fun background(block: () -> Unit) {
        if (destroyed) return
        try { io.execute { if (!destroyed) block() } } catch (_: RejectedExecutionException) { }
    }''')
# All journal callbacks and transport completion callbacks must tolerate teardown.
s = loc.read_text(encoding='utf-8').replace('io.execute {', 'background {')
s = s.replace('try { background { if (!destroyed) block() } }', 'try { io.execute { if (!destroyed) block() } }')
loc.write_text(s, encoding='utf-8')
replace(loc, '        state.restore(engine)', '''        state.restore(engine) // one-time v1 preference migration; DB checkpoint takes precedence
        local.meta("engine_checkpoint")?.let { checkpoint ->
            val latest = JournalCheckpoint.restore(engine, checkpoint)
            lastAcceptedGpsTime = latest.timeMs
            lastAccepted = Location("journal").apply { latitude = latest.point.lat; longitude = latest.point.lon; accuracy = latest.point.accuracyM.toFloat(); time = latest.timeMs }
        }''')
replace(loc, '''        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser != null) {
            onAuthenticated()
        } else {
            auth.signInAnonymously()
                .addOnSuccessListener { onAuthenticated() }
                .addOnFailureListener { e -> publishLocalStatus("auth_error:${e.javaClass.simpleName}") }
        }''', '        ensureAuthenticated()')
replace(loc, '    private fun onAuthenticated() {', '''    private fun ensureAuthenticated() {
        if (destroyed) return
        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser != null) {
            if (commandListener == null) onAuthenticated()
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (authInFlight || now < authRetryAt) return
        authInFlight = true
        authRetryAt = now + 60_000L
        auth.signInAnonymously().addOnSuccessListener { authInFlight = false; if (!destroyed) onAuthenticated() }
            .addOnFailureListener { e -> authInFlight = false; publishLocalStatus("auth_error:${e.javaClass.simpleName}") }
    }

    private fun onAuthenticated() {
        if (destroyed) return''')
replace(loc, '        consumePendingFcmRefresh()\n        cloud.enableNetwork()', '        resumeDurableRefresh()\n        consumePendingFcmRefresh()\n        cloud.enableNetwork()')
replace(loc, '            handler.postDelayed(this, FALLBACK_POLL_MS)', '            ensureAuthenticated()\n            retryRefreshResult()\n            handler.postDelayed(this, if (System.currentTimeMillis() - lastFirebaseHealthPublishAt < 120_000L && activeRefreshId == 0L) 120_000L else FALLBACK_POLL_MS)')
replace(loc, '        val now = System.currentTimeMillis()\n        updateNetworkHistory(now)\n        if (!networkState().second)', '        if (destroyed) return@Runnable\n        ensureAuthenticated()\n        val now = System.currentTimeMillis()\n        updateNetworkHistory(now)\n        if (!networkState().second)')
replace(loc, '''            client.requestLocationUpdates(request, callback, mainLooper)
            trackingActive = true''', '''            trackingActive = true
            client.requestLocationUpdates(request, callback, mainLooper)
                .addOnFailureListener { e -> trackingActive = false; publishLocalStatus("tracking_register:${e.javaClass.simpleName}") }''')
replace(loc, '        val fine = hasFineLocation()\n        val locationOn = isLocationEnabled()', '''        if (destroyed) return
        ensureAuthenticated()
        if (FirebaseAuth.getInstance().currentUser != null && commandListener == null && SystemClock.elapsedRealtime() >= listenerRetryAt) listenCommands()
        val fine = hasFineLocation()
        val locationOn = isLocationEnabled()''')
replace(loc, '''                    publishLocalStatus("command_listener:${error.code}")
                    return@addSnapshotListener''', '''                    publishLocalStatus("command_listener:${error.code}")
                    commandListener?.remove(); commandListener = null
                    listenerRetryAt = SystemClock.elapsedRealtime() + 30_000L
                    return@addSnapshotListener''')
replace(loc, '                if (d == null || !d.exists()) return@addSnapshotListener', '                if (destroyed || d == null || !d.exists()) return@addSnapshotListener')
replace(loc, '''        pendingFcmRefreshRequestId = 0L
        prefs.edit().remove(KEY_PENDING_FCM_REFRESH_ID).apply()
        if (requestId > lastRefreshSeen) {
            processRefreshRequest(requestId, "fcm")
        }''', '''        processRefreshRequest(requestId, "fcm")''')
replace(loc, '''        if (ts <= lastRefreshSeen) return
        lastRefreshSeen = ts
        getSharedPreferences("tracking_diag", MODE_PRIVATE).edit().putLong("last_refresh_seen", ts).apply()
        publishLocalStatus("refresh_received_$transport")
        publishRefreshAck(ts)
        requestImmediate(ts)''', '''        if (destroyed || ts <= 0L) return
        val prefs = getSharedPreferences("tracking_diag", MODE_PRIVATE)
        val terminal = JSONObject(local.meta("refresh_result") ?: "{}")
        if (terminal.optLong("request") >= ts) { retryRefreshResult(); return }
        if (ts <= prefs.getLong("refresh_delivered_for_v2210", 0L)) return
        if (activeRefreshId == ts && acquiring) return
        if (activeRefreshId > ts) return
        try { local.putMeta("refresh_pending", ts.toString()) } catch (e: Exception) {
            publishLocalStatus("refresh_store_failed:${e.javaClass.simpleName}"); return
        } // durable before ACK/acquisition
        activeRefreshId = ts
        publishLocalStatus("refresh_received_$transport")
        if (System.currentTimeMillis() - ts > REFRESH_TTL_MS || ts > System.currentTimeMillis() + 60_000L) {
            publishRefreshFailure(ts, "Yêu cầu đã hết hạn"); return
        }
        publishRefreshAck(ts)
        requestImmediate(ts)''')
replace(loc, '    private fun processLocationReminder', '''    private fun resumeDurableRefresh() {
        local.meta("refresh_pending")?.toLongOrNull()?.let { processRefreshRequest(it, "restart") }
        retryRefreshResult()
    }

    private fun saveRefreshResult(request: Long, fields: Map<String, Any?>) {
        val envelope = JSONObject().put("request", request).put("fields", JSONObject(fields.filterValues { it !is FieldValue }))
        try {
            local.putMeta("refresh_result", envelope.toString())
            local.putMeta("refresh_pending", "0")
        } catch (e: Exception) { publishLocalStatus("refresh_store_failed:${e.javaClass.simpleName}"); return }
        activeRefreshId = 0L
        retryRefreshResult()
    }

    private fun retryRefreshResult() {
        if (destroyed || FirebaseAuth.getInstance().currentUser == null) return
        val envelope = JSONObject(local.meta("refresh_result") ?: "{}")
        val request = envelope.optLong("request")
        val prefs = getSharedPreferences("tracking_diag", MODE_PRIVATE)
        if (request <= 0L || request <= prefs.getLong("refresh_delivered_for_v2210", 0L)) return
        val json = envelope.getJSONObject("fields")
        val fields = mutableMapOf<String, Any?>()
        json.keys().forEach { key -> fields[key] = if (json.isNull(key)) null else json.get(key) }
        fun confirmed() { prefs.edit().putLong("refresh_delivered_for_v2210", maxOf(request, prefs.getLong("refresh_delivered_for_v2210", 0L))).commit() }
        cloud.collection("devices").document(CHILD_DOC).set(fields, SetOptions.merge()).addOnSuccessListener { confirmed() }
            .addOnFailureListener { e -> publishLocalStatus("refresh_result:${e.javaClass.simpleName}") }
        ChildHttpsBridge.patch(fields) { ok, _ -> if (ok) confirmed() }
    }

    private fun processLocationReminder''')
replace(loc, '            "refreshResult" to "locating"', '''            "refreshResult" to "locating",
            "refreshServiceFor" to requestId,
            "refreshServiceAt" to now,
            "refreshReceivedFor" to requestId,
            "refreshReceivedAt" to now''')
replace(loc, '        val problem = usabilityProblem(location)', '''        if (destroyed || (refreshFor != null && refreshFor != activeRefreshId)) return
        val problem = usabilityProblem(location)''')
replace(loc, '        val previous = lastAccepted', '''        if (location.time < lastAcceptedGpsTime) {
            if (refreshFor != null) publishRefreshFailure(refreshFor, "Tọa độ GPS không mới hơn dữ liệu đã lưu")
            return
        }
        val previous = lastAccepted''')
replace(loc, '((location.elapsedRealtimeNanos - previous.elapsedRealtimeNanos) / 1_000_000_000.0).coerceAtLeast(1.0)', '((location.time - previous.time) / 1000.0).coerceAtLeast(1.0)')
replace(loc, '''        lastAccepted = Location(location)
        RecoveryGeofenceManager.update(this, location)
        handle(location, refreshFor)''', '''        handle(location, refreshFor)''')
replace(loc, '        if (location.latitude == 0.0', '''        if (!location.latitude.isFinite() || !location.longitude.isFinite() || location.latitude !in -90.0..90.0 || location.longitude !in -180.0..180.0) return "Tọa độ không hợp lệ"
        if (!location.accuracy.isFinite() || location.accuracy < 0f) return "Độ chính xác không hợp lệ"
        if (location.time <= 0L || location.time > System.currentTimeMillis() + 60_000L) return "Timestamp GPS không hợp lệ"
        if (location.latitude == 0.0''')
replace(loc, '''        val events = if (engine.currentVisit() == null && engine.currentTrip() == null) engine.seedVisit(sample) else engine.accept(sample)
        state.save(engine)
        events.forEach(::queueEvent)''', '''        val before = engine.snapshot()
        val sampleId = EventIdentity.sampleId(sampleTime, l.latitude, l.longitude)
        val duplicate = lastAcceptedGpsTime == sampleTime && lastAccepted?.latitude == l.latitude && lastAccepted?.longitude == l.longitude
        val events = if (duplicate) emptyList() else if (engine.currentVisit() == null && engine.currentTrip() == null) engine.seedVisit(sample) else engine.accept(sample)
        val journal = events.map { eventJson(it).toString() }.toMutableList()
        if (!duplicate) journal += JSONObject().put("type", "location_sample").put("id", sampleId)
            .put("lat", l.latitude).put("lon", l.longitude).put("accuracy", l.accuracy.toDouble()).put("time", sampleTime).toString()
        try {
            local.commitSample(journal, JournalCheckpoint.encode(engine, sample))
        } catch (e: Exception) {
            engine.restoreSnapshot(before)
            publishLocalStatus("journal_write_failed:${e.javaClass.simpleName}")
            if (refreshFor != null) publishRefreshFailure(refreshFor, "Không lưu được nhật ký trên máy")
            return
        }
        pendingEventCount = local.count()
        lastAccepted = Location(l); lastAcceptedGpsTime = sampleTime
        RecoveryGeofenceManager.update(this, l)''')
replace(loc, '                "lastSeen" to now,', '                "lastSeen" to sampleTime,\n                "locationTime" to sampleTime,')
replace(loc, 'lastSamplePersisted?.distanceTo(l)', 'lastAccepted?.distanceTo(l)')
replace(loc, '            val firebaseStart = SystemClock.elapsedRealtime()', '            if (refreshFor != null) saveRefreshResult(refreshFor, payload)\n            val firebaseStart = SystemClock.elapsedRealtime()')
replace(loc, '        maybeQueueLocationSample(l, sampleTime)\n        flushPending()', '        flushPending()')
s = loc.read_text(encoding='utf-8')
start=s.index('    private fun maybeQueueLocationSample(');end=s.index('    private fun queueEvent(',start)
s=s[:start]+s[end:];s=s.replace('    private fun queueEvent(e: Event) {', '    private fun eventJson(e: Event): JSONObject {')
start=s.index('        background {\n            local.insert(o.toString())',s.index('    private fun eventJson('));end=s.index('\n    private fun queueNetworkEvent',start)
s=s[:start]+'        return o\n    }\n'+s[end:]
loc.write_text(s,encoding='utf-8')
replace(loc, 'val documentId = "${m["type"]}-${m["id"]}-${m["time"]}"', 'val documentId = EventIdentity.documentId(o)')
replace(loc, '        fun fallback(reason: String) {\n            if (resolved.get()) return', '        val fallbackStarted = AtomicBoolean(false)\n        fun fallback(reason: String) {\n            if (destroyed || resolved.get() || !fallbackStarted.compareAndSet(false, true)) return')
replace(loc, '    private fun requestImmediate(refreshFor: Long?) {', '''    private fun requestImmediate(refreshFor: Long?) {
        if (destroyed) return
        if (acquiring && refreshFor == null) return
        if (acquiring) acquisitionToken?.cancel()
        val token = CancellationTokenSource()
        acquisitionToken = token
        acquiring = true
        if (refreshFor != null) {
            val progress = mapOf<String, Any?>("refreshLocatingFor" to refreshFor, "refreshLocatingAt" to System.currentTimeMillis())
            cloud.collection("devices").document(CHILD_DOC).set(progress, SetOptions.merge())
            ChildHttpsBridge.patch(progress)
        }
        fun finished() { if (acquisitionToken === token) { acquiring = false; acquisitionToken = null } }''')
replace(loc, '''        if (!hasFineLocation()) {
            if (refreshFor != null)''', '''        if (!hasFineLocation()) {
            finished()
            if (refreshFor != null)''')
replace(loc, '''        if (!isLocationEnabled()) {
            if (refreshFor != null)''', '''        if (!isLocationEnabled()) {
            finished()
            if (refreshFor != null)''')
replace(loc, '''            client.getCurrentLocation(request, null)
                .addOnSuccessListener { l ->''', '''            client.getCurrentLocation(request, token.token)
                .addOnSuccessListener { l ->
                    if (destroyed || token.token.isCancellationRequested) return@addOnSuccessListener
                    finished()''')
replace(loc, '''                .addOnFailureListener { e ->
                    if (refreshFor != null)''', '''                .addOnFailureListener { e ->
                    if (destroyed || token.token.isCancellationRequested) return@addOnFailureListener
                    finished()
                    if (refreshFor != null)''')
replace(loc, '''        } catch (e: SecurityException) {
            if (refreshFor != null)''', '''        } catch (e: SecurityException) {
            finished()
            if (refreshFor != null)''')
replace(loc, '''    private fun publishRefreshFailure(requestId: Long, message: String) {
        if (FirebaseAuth.getInstance().currentUser == null) return''', '''    private fun publishRefreshFailure(requestId: Long, message: String) {
        if (destroyed || (activeRefreshId > 0L && requestId != activeRefreshId)) return''')
replace(loc, '''        payload.putAll(diagnosticFields(now))
        cloud.collection("devices").document(CHILD_DOC).set(payload, SetOptions.merge())
            .addOnFailureListener { e -> publishLocalStatus("refresh_fail_write:${e.javaClass.simpleName}") }
        ChildHttpsBridge.patch(payload) { ok, _ -> if (ok) publishLocalStatus("refresh_fail_https") }''', '''        payload.putAll(diagnosticFields(now))
        saveRefreshResult(requestId, payload)''')
replace(loc, '            "firebaseWriteOkAt" to now\n', '            "lastCloudHeartbeatAttemptAt" to now\n')
replace(loc, '                publishLocalStatus("heartbeat_firebase")', '''                lastCloudHeartbeatSuccessAt = System.currentTimeMillis()
                getSharedPreferences("tracking_diag", MODE_PRIVATE).edit().putLong("last_cloud_heartbeat_success_v2210", lastCloudHeartbeatSuccessAt).apply()
                publishLocalStatus("heartbeat_firebase")''')
replace(loc, 'ChildHttpsBridge.patch(rest) { ok, _ -> if (ok) publishLocalStatus("heartbeat_https") }', '''ChildHttpsBridge.patch(rest) { ok, _ -> if (ok) {
            lastCloudHeartbeatSuccessAt = System.currentTimeMillis()
            getSharedPreferences("tracking_diag", MODE_PRIVATE).edit().putLong("last_cloud_heartbeat_success_v2210", lastCloudHeartbeatSuccessAt).apply()
            publishLocalStatus("heartbeat_https")
        } }''')
replace(loc, '    private fun restartCloudAfterRouteChange() {', '    private fun restartCloudAfterRouteChange() {\n        if (destroyed) return\n        ensureAuthenticated()')
replace(loc, '                cloud.enableNetwork().addOnCompleteListener {', '                if (destroyed) return@addOnCompleteListener\n                cloud.enableNetwork().addOnCompleteListener {\n                    if (destroyed) return@addOnCompleteListener')
replace(loc, '            "lastCloudHeartbeatAttemptAt" to lastCloudHeartbeatAttemptAt,', '''            "lastCloudHeartbeatAttemptAt" to lastCloudHeartbeatAttemptAt,
            "lastCloudHeartbeatSuccessAt" to getSharedPreferences("tracking_diag", MODE_PRIVATE).getLong("last_cloud_heartbeat_success_v2210", 0L),
            "processExitReason" to getSharedPreferences("tracking_diag", MODE_PRIVATE).getInt("last_process_exit_reason", 0),
            "processExitImportance" to getSharedPreferences("tracking_diag", MODE_PRIVATE).getInt("last_process_exit_importance_v2210", 0),
            "pendingJourneyCount" to pendingEventCount,
            "geofenceRegisteredAt" to getSharedPreferences("tracking_diag", MODE_PRIVATE).getLong("recovery_geofence_registered_at_v229", 0L),
            "geofenceTriggeredAt" to getSharedPreferences("tracking_diag", MODE_PRIVATE).getLong("recovery_geofence_event_at_v229", 0L),
            "fcmTokenConfirmedAt" to getSharedPreferences("tracking_diag", MODE_PRIVATE).getLong("fcm_token_cloud_at", 0L),
            "fcmDeliveredPriority" to getSharedPreferences("tracking_diag", MODE_PRIVATE).getInt("fcm_wake_priority", 0),''')
replace(loc, '    override fun onDestroy() {', '''    override fun onDestroy() {
        destroyed = true
        acquisitionToken?.cancel()
        acquiring = false
        handler.removeCallbacksAndMessages(null)''')
replace(loc, '        private const val CHILD_DOC = "child-01"', '        private const val REFRESH_TTL_MS = 15 * 60_000L\n        private const val CHILD_DOC = "child-01"')
replace(loc, '    companion object {', '    companion object {\n        @Volatile var running: Boolean = false\n            private set')
replace(loc, '        startAsForeground()\n        registerNetworkRecovery()', '        startAsForeground()\n        running = true\n        registerNetworkRecovery()')
replace(loc, '        destroyed = true\n        acquisitionToken?.cancel()', '        destroyed = true\n        running = false\n        acquisitionToken?.cancel()')
replace(loc, '        if (authInFlight || now < authRetryAt) return', '        if (authInFlight && now >= authRetryAt) authInFlight = false\n        if (authInFlight || now < authRetryAt) return')
replace(loc, '    private fun trustedLocationTime(location: Location, now: Long): Long {\n        val t = location.time\n        return if (t > 0L && t <= now + 60_000L && now - t <= TRUSTED_LOCATION_TIME_MAX_AGE_MS) t else now\n    }', '    private fun trustedLocationTime(location: Location, now: Long): Long = location.time // validated original GPS timestamp')
replace(loc, '''        background {
            local.insert(o.toString())
            pendingEventCount = (pendingEventCount + 1).coerceAtMost(PENDING_REPORT_LIMIT)
            flushPending()
        }
    }

    private fun flushPending()''', '''        local.commitEventAndMeta(o.toString(), KEY_NETWORK_OFFLINE_SINCE, if (type == "network_offline") eventTime.toString() else "0")
        pendingEventCount = local.count()
        flushPending()
    }

    private fun flushPending()''')
replace(loc, '        val offlineSince = prefs.getLong(KEY_NETWORK_OFFLINE_SINCE, 0L)', '        val offlineSince = local.meta(KEY_NETWORK_OFFLINE_SINCE)?.toLongOrNull() ?: prefs.getLong(KEY_NETWORK_OFFLINE_SINCE, 0L)')
replace(loc, '''                prefs.edit().putLong(KEY_NETWORK_OFFLINE_SINCE, now).apply()
                queueNetworkEvent("network_offline", now)''', '''                queueNetworkEvent("network_offline", now)
                prefs.edit().putLong(KEY_NETWORK_OFFLINE_SINCE, now).apply()''')
replace(loc, '            val duration = now - offlineSince\n            prefs.edit()', '            val duration = now - offlineSince\n            queueNetworkEvent("network_restored", now, offlineSince, duration)\n            prefs.edit()')
replace(loc, '            queueNetworkEvent("network_restored", now, offlineSince, duration)\n            publishLocalStatus', '            publishLocalStatus')
replace(loc, '            if (jumpM > 10_000 && elapsedSec < 600 && impliedKmh > 220', '            if (jumpM > 1_500 && elapsedSec < 600 && impliedKmh > 250')
replace(loc, '        val problem = usabilityProblem(location)', '        val problem = LocationPolicy.problem(location, lastAccepted, lastAcceptedGpsTime)')
replace(loc, '            is Event.TripPointAdded -> {\n                o.put("type", "trip_point")', '            is Event.TripPointAdded -> {\n                o.put("eventId", "trip_point-${e.trip.id}-${EventIdentity.sampleId(e.sample.timeMs, e.sample.point.lat, e.sample.point.lon)}")\n                o.put("type", "trip_point")')
watchdog = child / 'ServiceWatchdogWorker.kt'
replace(watchdog, 'val stale = lastLocalHeartbeatAt <= 0L ||', 'val stale = !LocationService.running || lastLocalHeartbeatAt <= 0L ||')

replace(loc, '        serviceStartedAt = System.currentTimeMillis()', '''        serviceStartedAt = System.currentTimeMillis()
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(packageName, 0, 1).firstOrNull()?.let { exit ->
                    getSharedPreferences("tracking_diag", MODE_PRIVATE).edit().putInt("last_process_exit_reason", exit.reason)
                        .putInt("last_process_exit_importance_v2210", exit.importance).putLong("last_process_exit_at", exit.timestamp).apply()
                }
            } catch (_: Exception) { }
        }''')
replace(loc, 'reason == "wifi_recovered" || reason == "cellular_lost"', 'reason == "wifi_recovered" || reason == "cellular_lost" || reason == "vpn_active"')
replace(loc, '            handler.postDelayed(networkRecovery, NETWORK_RECOVERY_DEBOUNCE_MS)\n            failoverManager.probeNow()', '            handler.postDelayed(networkRecovery, NETWORK_RECOVERY_DEBOUNCE_MS)\n            if (!destroyed) failoverManager.probeNow()')
replace(loc, '        getSystemService(LocationManager::class.java).isLocationEnabled', '        androidx.core.location.LocationManagerCompat.isLocationEnabled(getSystemService(LocationManager::class.java))')
replace(loc, 'if (probeFailureHint && ::failoverManager.isInitialized) failoverManager.probeNow()', 'if (probeFailureHint && ::failoverManager.isInitialized) failoverManager.reportServerFailure()')
replace(loc, '            "wifiServerFailureCount" to failover.wifiServerFailureCount,', '            "wifiServerFailureCount" to failover.wifiServerFailureCount,\n            "actualServerFailureCount" to failover.actualServerFailureCount,')
replace(loc, '                    publishLocalStatus("location_sent")', '                    failoverManager.reportServerSuccess()\n                    publishLocalStatus("location_sent")')
replace(loc, 'if (ok) publishLocalStatus("location_https_sent")', 'if (ok) { failoverManager.reportServerSuccess(); publishLocalStatus("location_https_sent") }')
replace(loc, '                publishLocalStatus("heartbeat_firebase")', '                failoverManager.reportServerSuccess()\n                publishLocalStatus("heartbeat_firebase")')
replace(loc, '            publishLocalStatus("heartbeat_https")', '            failoverManager.reportServerSuccess()\n            publishLocalStatus("heartbeat_https")')
replace(loc, '                publishLocalStatus("event_sent_$transport")', '                failoverManager.reportServerSuccess()\n                publishLocalStatus("event_sent_$transport")')
replace(loc, '        if (destroyed) return\n        ensureAuthenticated()\n        WakeTokenSyncWorker.schedule(this)', '        if (destroyed) return\n        authRetryAt = 0L\n        authInFlight = false\n        ensureAuthenticated()\n        WakeTokenSyncWorker.schedule(this)')
replace(loc, 'Build.VERSION.SDK_INT >= 28 && getSystemService(UsageStatsManager::class.java).appStandbyBucket == UsageStatsManager.STANDBY_BUCKET_RESTRICTED', 'Build.VERSION.SDK_INT >= 30 && getSystemService(UsageStatsManager::class.java).appStandbyBucket == UsageStatsManager.STANDBY_BUCKET_RESTRICTED')
starter = child / 'RecoveryStarter.kt'
replace(starter, '            app.getSystemService(LocationManager::class.java).isLocationEnabled', '            androidx.core.location.LocationManagerCompat.isLocationEnabled(app.getSystemService(LocationManager::class.java))')
replace(loc, '        startAsForeground()\n        running = true', '''        try {
            startAsForeground()
            running = true
        } catch (e: Exception) {
            publishLocalStatus("foreground_start_failed:${e.javaClass.simpleName}")
            stopSelf()
            return
        }''')
replace(loc, '    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {', '    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {\n        if (!running) { stopSelf(); return START_NOT_STICKY }')
replace(watchdog, '        if (!stale && !recoveryRequested) {', '        if (stale) RecoveryGeofenceManager.rearmLatestObserved(app, "watchdog")\n        if (!stale && !recoveryRequested) {')

parent = Path('appsrc/parent-app/src/main/java/com/family/parent/MainActivity.kt')
replace(parent, 'if (refreshRequestId > 0L && wakeFor == refreshRequestId && commandConfirmedFor == refreshRequestId)', 'if (refreshRequestId > 0L && wakeFor == refreshRequestId && commandConfirmedFor == refreshRequestId && (d.getLong("refreshAckFor") ?: 0L) != refreshRequestId)')
replace(parent, 'val payload = mapOf<String, Any?>("refreshRequestedAt" to requestAt)', 'val payload = mapOf<String, Any?>("refreshRequestedAt" to requestAt, "refreshExpiresAt" to requestAt + 15 * 60_000L)')
replace(parent, '                processReminderState(\n                    d.getLong("locationReminderAckFor")', '''                if (refreshRequestId > 0L) {
                    refreshText = when (refreshRequestId) {
                        d.getLong("refreshLocatingFor") -> "Đang lấy vị trí mới..."
                        d.getLong("refreshServiceFor") -> "Service Máy Con đang hoạt động..."
                        d.getLong("refreshReceivedFor") -> "Máy Con đã nhận yêu cầu..."
                        else -> refreshText
                    }
                }
                processReminderState(
                    d.getLong("locationReminderAckFor")''')
bridge = Path('appsrc/parent-app/src/main/java/com/family/parent/ParentHttpsBridge.kt')
replace(bridge, '        val refreshResult: String? = null,', '''        val refreshResult: String? = null,
        val refreshReceivedFor: Long = 0L,
        val refreshServiceFor: Long = 0L,
        val refreshLocatingFor: Long = 0L,
        val wakeDispatchFor: Long = 0L,
        val wakeDispatchResult: String? = null,''')
replace(bridge, '                                refreshResult = string(fields, "refreshResult"),', '''                                refreshResult = string(fields, "refreshResult"),
                                refreshReceivedFor = long(fields, "refreshReceivedFor"),
                                refreshServiceFor = long(fields, "refreshServiceFor"),
                                refreshLocatingFor = long(fields, "refreshLocatingFor"),
                                wakeDispatchFor = long(fields, "wakeDispatchFor"),
                                wakeDispatchResult = string(fields, "wakeDispatchResult"),''')
replace(parent, '        processReminderState(s.locationReminderAckFor, s.locationReminderResult)', '''        if (refreshRequestId > 0L) refreshText = when (refreshRequestId) {
            s.refreshLocatingFor -> "Đang lấy vị trí mới..."
            s.refreshServiceFor -> "Service Máy Con đang hoạt động..."
            s.refreshReceivedFor -> "Máy Con đã nhận yêu cầu..."
            s.wakeDispatchFor -> if (s.wakeDispatchResult == "sent") "Backend đã phát yêu cầu đánh thức..." else "Kênh đánh thức: ${s.wakeDispatchResult ?: "đang chờ"}"
            else -> refreshText
        }
        processReminderState(s.locationReminderAckFor, s.locationReminderResult)''')
for module in ['parent-app', 'child-app']:
    p=Path('appsrc')/module/'build.gradle.kts'
    s=p.read_text(encoding='utf-8').replace('versionCode = 31','versionCode = 32').replace('versionName = "2.2.9"','versionName = "2.2.10"')
    p.write_text(s,encoding='utf-8')
test = Path('appsrc/child-app/src/test/java/com/family/child/JournalReliabilityTest.kt')
test.parent.mkdir(parents=True, exist_ok=True)
shutil.copyfile('ci-patches/JournalReliabilityTestV2210.kt', test)
p = Path('appsrc/child-app/build.gradle.kts')
with p.open('a', encoding='utf-8') as f: f.write('\nandroid { testOptions { unitTests { isIncludeAndroidResources = true } } }\ndependencies { testImplementation("junit:junit:4.13.2"); testImplementation("org.robolectric:robolectric:4.17") }\n')
print('v2.2.10 reliability patch applied')
