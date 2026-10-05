package com.family.child

import android.Manifest
import android.app.*
import android.app.usage.UsageStatsManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.family.core.*
import com.google.android.gms.location.*
import com.google.android.gms.tasks.CancellationTokenSource
import java.util.concurrent.RejectedExecutionException
import com.google.firebase.auth.FirebaseAuth
import com.family.enrollment.EnrollmentClient
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.SetOptions
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class LocationService : Service() {
    private lateinit var client: FusedLocationProviderClient
    private val engine = VisitEngine()
    private val cloud by lazy { FirebaseFirestore.getInstance() }
    private val local by lazy { PendingStore(this) }
    private val io = Executors.newSingleThreadExecutor()
    private val journalThread = android.os.HandlerThread("family-location-journal")
    private lateinit var journalHandler: Handler
    private fun journal(block: () -> Unit) {
        if (!::journalHandler.isInitialized || destroyed) return
        val action = { try { block() } catch (e: Exception) { publishLocalStatus("journal_error:${e.javaClass.simpleName}") }; Unit }
        if (Looper.myLooper() == journalThread.looper) action() else journalHandler.post { action() }
    }
    private val flushing = AtomicBoolean(false)
    private lateinit var state: StateStore
    private var commandListener: ListenerRegistration? = null
    private var lastRefreshSeen = 0L
    private var lastLocationReminderSeen = 0L
    private var pendingFcmRefreshRequestId = 0L
    private var lastAccepted: Location? = null
    private var lastSamplePersisted: Location? = null
    private var lastSamplePersistedAt = 0L
    @Volatile private var destroyed = false
    private var authInFlight = false
    private var authRetryAt = 0L
    private var listenerRetryAt = 0L
    private var listenerGeneration = 0L
    private var routeGeneration = 0L
    private var lastFreshFixAttemptElapsed = 0L
    private var lastAcceptedGpsTime = 0L
    @Volatile private var activeRefreshId = 0L
    private var acquiring = false
    private var acquisitionToken: CancellationTokenSource? = null
    @Volatile private var lastCloudHeartbeatSuccessAt = 0L
    private var trackingActive = false
    private fun background(block: () -> Unit) {
        if (destroyed) return
        try { io.execute { if (!destroyed) block() } } catch (_: RejectedExecutionException) { }
    }
    private var lastHealthSignature = ""
    private var networkCallbackRegistered = false
    private var lastFirebaseHealthPublishAt = 0L
    private var serviceStartedAt = 0L
    private lateinit var failoverManager: NetworkFailoverManager
    private var serviceInstanceId = ""
    private var serviceStartCount = 0
    @Volatile private var lastLocalServiceHeartbeatAt = 0L
    @Volatile private var lastGpsCallbackAt = 0L
    @Volatile private var lastCloudHeartbeatAttemptAt = 0L
    @Volatile private var pendingEventCount = 0
    private var syncSessionActive = false
    private var syncSessionInitialPending = 0
    private var syncSessionUploaded = 0
    private var lastSyncProgressPublishedAt = 0L
    private val handler = Handler(Looper.getMainLooper())

    private val localServiceHeartbeat = object : Runnable {
        override fun run() {
            writeLocalServiceHeartbeat(System.currentTimeMillis())
            handler.postDelayed(this, LOCAL_SERVICE_HEARTBEAT_MS)
        }
    }

    private val heartbeat = object : Runnable {
        override fun run() {
            publishHeartbeat()
            handler.postDelayed(this, HEARTBEAT_MS)
        }
    }

    private val fallbackPoll = object : Runnable {
        override fun run() {
            pollRestCommand()
            ensureAuthenticated()
            retryRefreshResult()
            handler.postDelayed(this, if (System.currentTimeMillis() - lastFirebaseHealthPublishAt < 120_000L && activeRefreshId == 0L) 120_000L else FALLBACK_POLL_MS)
        }
    }

    private val healthCheck = object : Runnable {
        override fun run() {
            evaluateTrackingHealth()
            handler.postDelayed(this, HEALTH_CHECK_MS)
        }
    }

    private val networkHistoryCheck = Runnable {
        updateNetworkHistory(System.currentTimeMillis())
    }

    private val networkRecovery = Runnable {
        if (destroyed) return@Runnable
        ensureAuthenticated()
        val now = System.currentTimeMillis()
        updateNetworkHistory(now)
        if (!networkState().second) {
            publishLocalStatus("network_recovery_waiting_validated")
            return@Runnable
        }
        publishLocalStatus("network_recovery")
        cloud.enableNetwork()
        if (cloudIdentityReady()) {
            listenCommands()
            pollRestCommand()
            publishHeartbeat()
            flushPending()
            if (hasFineLocation() && isLocationEnabled()) requestImmediate(null)
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            handler.removeCallbacks(networkHistoryCheck)
            handler.removeCallbacks(networkRecovery)
            handler.postDelayed(networkRecovery, NETWORK_RECOVERY_DEBOUNCE_MS)
            if (!destroyed) failoverManager.probeNow()
        }

        override fun onLost(network: Network) {
            publishLocalStatus("network_lost")
            handler.removeCallbacks(networkHistoryCheck)
            handler.postDelayed(networkHistoryCheck, NETWORK_LOSS_CONFIRM_MS)
        }
    }

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            lastGpsCallbackAt = System.currentTimeMillis()
            result.locations.forEach { handleCandidate(it, null) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Meet the promotion deadline before database/SDK/job/geofence work.
        try { startAsForeground(); running = true }
        catch (e: Exception) { publishLocalStatus("foreground_start_failed:${e.javaClass.simpleName}"); stopSelf(); return }
        journalThread.start()
        journalHandler = Handler(journalThread.looper)
        serviceStartedAt = System.currentTimeMillis()
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(packageName, 0, 1).firstOrNull()?.let { exit ->
                    getSharedPreferences("tracking_diag", MODE_PRIVATE).edit().putInt("last_process_exit_reason", exit.reason)
                        .putInt("last_process_exit_importance_v2210", exit.importance).putLong("last_process_exit_at", exit.timestamp).apply()
                }
            } catch (_: Exception) { }
        }
        serviceInstanceId = UUID.randomUUID().toString()
        val servicePrefs = getSharedPreferences("tracking_diag", MODE_PRIVATE)
        serviceStartCount = servicePrefs.getInt(KEY_SERVICE_START_COUNT, 0) + 1
        servicePrefs.edit()
            .putInt(KEY_SERVICE_START_COUNT, serviceStartCount)
            .putString(KEY_SERVICE_INSTANCE_ID, serviceInstanceId)
            .putLong(KEY_SERVICE_STARTED_AT_LOCAL, serviceStartedAt)
            .apply()
        writeLocalServiceHeartbeat(serviceStartedAt)
        ServiceWatchdogWorker.schedule(this)
        UnusedAppRestrictionProbe.refresh(this)
        WakeTokenSyncWorker.schedule(this)
        RecoveryGeofenceManager.rearmLatestObserved(this, "service_create")
        client = LocationServices.getFusedLocationProviderClient(this)
        state = StateStore(this)
        failoverManager = NetworkFailoverManager(this) { reason ->
            publishLocalStatus("network_route_$reason")
            if (reason == "cellular_failover" || reason == "wifi_recovered" || reason == "cellular_lost" || reason == "vpn_active") {
                restartCloudAfterRouteChange()
            }
            publishFailoverState(reason)
        }
        journal {
            state.restore(engine) // one-time v1 preference migration; DB checkpoint takes precedence
            local.meta("engine_checkpoint")?.let { checkpoint ->
                val latest = JournalCheckpoint.restore(engine, checkpoint)
                lastAcceptedGpsTime = latest.timeMs
                lastAccepted = Location("journal").apply { latitude = latest.point.lat; longitude = latest.point.lon; accuracy = latest.point.accuracyM.toFloat(); time = latest.timeMs }
            }
            background {
                pendingEventCount = try { local.batch(PENDING_REPORT_LIMIT).size } catch (_: Exception) { 0 }
            }
            val prefs = getSharedPreferences("tracking_diag", MODE_PRIVATE)
            local.meta(KEY_NETWORK_OFFLINE_SINCE)?.toLongOrNull()?.let { prefs.edit().putLong(KEY_NETWORK_OFFLINE_SINCE,it).apply() }
            lastRefreshSeen = prefs.getLong("last_refresh_seen", 0L)
            lastLocationReminderSeen = prefs.getLong("last_location_reminder_seen", 0L)
            pendingFcmRefreshRequestId = prefs.getLong(KEY_PENDING_FCM_REFRESH_ID, 0L)
        }
        ChildWakeMessagingService.refreshAndSyncToken(this)
        registerNetworkRecovery()
        failoverManager.start()
        publishLocalStatus("starting")

        ensureAuthenticated()

        requestTracking()
        handler.postDelayed(localServiceHeartbeat, LOCAL_SERVICE_HEARTBEAT_MS)
        handler.postDelayed(heartbeat, HEARTBEAT_MS)
        handler.postDelayed(fallbackPoll, 3_000L)
        handler.postDelayed(healthCheck, 10_000L)
    }

    private fun cloudIdentityReady() = EnrollmentClient.registered(this, "child")

    private fun ensureAuthenticated() {
        if (destroyed) return
        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser != null) {
            if (commandListener == null) onAuthenticated()
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (authInFlight && now >= authRetryAt) authInFlight = false
        if (authInFlight || now < authRetryAt) return
        authInFlight = true
        authRetryAt = now + 60_000L
        auth.signInAnonymously().addOnSuccessListener { authInFlight = false; if (!destroyed) onAuthenticated() }
            .addOnFailureListener { e -> authInFlight = false; publishLocalStatus("auth_error:${e.javaClass.simpleName}") }
    }

    private fun onAuthenticated() {
        if (destroyed) return
        publishLocalStatus("auth_ok")
        WakeTokenSyncWorker.schedule(this)
        if (!cloudIdentityReady()) { publishLocalStatus("enrollment_pending"); return }
        resumeDurableRefresh()
        consumePendingFcmRefresh()
        cloud.enableNetwork()
        listenCommands()
        flushPending()
        publishHeartbeat()
        pollRestCommand()
        requestImmediate(null)
    }

    private fun registerNetworkRecovery() {
        try {
            getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)
            networkCallbackRegistered = true
        } catch (e: Exception) {
            publishLocalStatus("network_callback:${e.javaClass.simpleName}")
        }
    }

    private fun startAsForeground() {
        val n = trackingNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            ServiceCompat.startForeground(this, TRACKING_NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(TRACKING_NOTIFICATION_ID, n)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!running) { stopSelf(); return START_NOT_STICKY }
        intent?.getStringExtra(RecoveryStarter.EXTRA_RECOVERY_REASON)?.takeIf { it.isNotBlank() }?.let { reason ->
            getSharedPreferences("tracking_diag", MODE_PRIVATE).edit()
                .putString("last_recovery_reason_v229", reason)
                .putLong("last_recovery_start_at_v229", System.currentTimeMillis())
                .apply()
        }
        val fcmRequestId = intent?.getLongExtra(EXTRA_FCM_REFRESH_REQUEST_ID, 0L) ?: 0L
        if (fcmRequestId > 0L) {
            pendingFcmRefreshRequestId = maxOf(pendingFcmRefreshRequestId, fcmRequestId)
            getSharedPreferences("tracking_diag", MODE_PRIVATE).edit()
                .putLong(KEY_PENDING_FCM_REFRESH_ID, pendingFcmRefreshRequestId)
                .putLong("fcm_service_wake_at", System.currentTimeMillis())
                .apply()
            if (cloudIdentityReady()) consumePendingFcmRefresh()
        }
        if (intent?.action == ACTION_TRACKING_NOTIFICATION_DISMISSED) {
            getSharedPreferences("tracking_diag", MODE_PRIVATE).edit()
                .putLong(KEY_TRACKING_NOTIFICATION_DISMISSED_AT, System.currentTimeMillis())
                .apply()
            publishLocalStatus("tracking_notification_dismissed")
            return START_STICKY
        }
        if (intent?.getBooleanExtra(ServiceWatchdogWorker.EXTRA_WATCHDOG_RECOVERY, false) == true) {
            publishLocalStatus("watchdog_recovery_start")
            writeLocalServiceHeartbeat(System.currentTimeMillis())
            evaluateTrackingHealth()
            if (::failoverManager.isInitialized) failoverManager.probeNow()
        }
        if (intent?.getBooleanExtra("immediate", false) == true) {
            evaluateTrackingHealth()
            if (hasFineLocation() && isLocationEnabled()) requestImmediate(null)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        getSharedPreferences("tracking_diag", MODE_PRIVATE).edit()
            .putLong("service_task_removed_at", System.currentTimeMillis())
            .apply()
        ServiceWatchdogWorker.requestImmediateRecovery(this, "task_removed")
        super.onTaskRemoved(rootIntent)
    }

    override fun onTrimMemory(level: Int) {
        getSharedPreferences("tracking_diag", MODE_PRIVATE).edit()
            .putInt("last_trim_memory_level", level)
            .putLong("last_trim_memory_at", System.currentTimeMillis())
            .apply()
        super.onTrimMemory(level)
    }

    private fun trackingNotification(): Notification {
        val id = PROTECTION_CHANNEL_ID
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(id, "Protection", NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            })
        }
        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val deletePi = PendingIntent.getService(
            this,
            1,
            Intent(this, LocationService::class.java).setAction(ACTION_TRACKING_NOTIFICATION_DISMISSED),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, id)
            .setSmallIcon(R.drawable.ic_guardian_shield_notification)
            .setContentTitle("Điện thoại của bạn đang được bảo vệ an toàn")
            .setContentIntent(pi)
            .setDeleteIntent(deletePi)
            .setOngoing(false)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun requestTracking() {
        if (trackingActive) return
        if (!hasFineLocation()) { publishLocalStatus("location_permission_missing"); return }
        if (!isLocationEnabled()) { publishLocalStatus("location_off"); return }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, TRACK_INTERVAL_MS)
            .setMinUpdateIntervalMillis(TRACK_FASTEST_MS)
            .setMinUpdateDistanceMeters(TRACK_DISTANCE_M)
            .setMaxUpdateDelayMillis(TRACK_MAX_DELAY_MS)
            .setWaitForAccurateLocation(false)
            .build()
        try {
            trackingActive = true
            client.requestLocationUpdates(request, callback, journalThread.looper)
                .addOnFailureListener { e -> trackingActive = false; publishLocalStatus("tracking_register:${e.javaClass.simpleName}") }
            publishLocalStatus("tracking")
        } catch (e: SecurityException) {
            trackingActive = false
            publishLocalStatus("tracking_security:${e.javaClass.simpleName}")
        }
    }

    private fun evaluateTrackingHealth() {
        if (destroyed) return
        ensureAuthenticated()
        if (cloudIdentityReady() && commandListener == null && SystemClock.elapsedRealtime() >= listenerRetryAt) listenCommands()
        val fine = hasFineLocation()
        val locationOn = isLocationEnabled()
        val elapsed = SystemClock.elapsedRealtime()
        // A distance-filtered stationary callback can legitimately be quiet.
        // Infrequent fresh acquisition checks the provider without claiming it died.
        if (fine && locationOn && trackingActive && !acquiring && elapsed - lastFreshFixAttemptElapsed >= 10 * 60_000L &&
            System.currentTimeMillis() - lastGpsCallbackAt >= 10 * 60_000L) requestImmediate(null)
        val signature = "$fine:$locationOn"

        if (!fine || !locationOn) {
            if (trackingActive) {
                client.removeLocationUpdates(callback)
                trackingActive = false
            }
        } else if (!trackingActive) {
            requestTracking()
            requestImmediate(null)
        }

        if (locationOn) getSystemService(NotificationManager::class.java).cancel(LOCATION_REMINDER_NOTIFICATION_ID)

        val locationWasOff = lastHealthSignature.endsWith(":false")
        if (signature != lastHealthSignature) {
            if (fine && locationOn && locationWasOff && isNotificationChannelEnabled(PROTECTION_CHANNEL_ID)) {
                getSystemService(NotificationManager::class.java).notify(TRACKING_NOTIFICATION_ID, trackingNotification())
                getSharedPreferences("tracking_diag", MODE_PRIVATE).edit()
                    .remove(KEY_TRACKING_NOTIFICATION_DISMISSED_AT)
                    .apply()
                publishLocalStatus("tracking_notification_restored_location_on")
            }
            lastHealthSignature = signature
            when {
                !fine -> publishCloudStatus("permission_missing", "Không có quyền vị trí chính xác")
                !locationOn -> publishCloudStatus("location_off", "Vị trí trên điện thoại đang tắt")
                else -> publishCloudStatus("tracking", null)
            }
        }
    }

    private fun listenCommands() {
        val generation = ++listenerGeneration
        commandListener?.remove()
        commandListener = cloud.collection("devices").document(CHILD_DOC)
            .addSnapshotListener(MetadataChanges.INCLUDE) { d, error ->
                if (destroyed || generation != listenerGeneration) return@addSnapshotListener
                if (error != null) {
                    publishLocalStatus("command_listener:${error.code}")
                    commandListener?.remove(); commandListener = null
                    listenerRetryAt = SystemClock.elapsedRealtime() + 30_000L
                    return@addSnapshotListener
                }
                if (destroyed || d == null || !d.exists()) return@addSnapshotListener
                if (!d.metadata.isFromCache) markFirebaseRealtimeHealthy()
                processRefreshRequest(d.getLong("refreshRequestedAt") ?: 0L, "firebase")
                processLocationReminder(
                    d.getLong("locationReminderRequestedAt") ?: 0L,
                    d.getLong("locationReminderExpiresAt") ?: 0L,
                    "firebase"
                )
            }
    }

    private fun markFirebaseRealtimeHealthy() {
        val now = System.currentTimeMillis()
        if (now - lastFirebaseHealthPublishAt < FIREBASE_HEALTH_THROTTLE_MS) return
        lastFirebaseHealthPublishAt = now
        val payload = mapOf<String, Any?>("firebaseRealtimeOkAt" to now)
        cloud.collection("devices").document(CHILD_DOC).set(payload, SetOptions.merge())
            .addOnSuccessListener { publishLocalStatus("firebase_realtime_ok") }
            .addOnFailureListener { e -> publishLocalStatus("firebase_health:${e.javaClass.simpleName}") }
        ChildHttpsBridge.patch(payload)
    }

    private fun pollRestCommand() {
        if (!cloudIdentityReady()) return
        ChildHttpsBridge.readDevice { state, error ->
            if (state != null) {
                publishLocalStatus("https_fallback_ok")
                processRefreshRequest(state.refreshRequestedAt, "https")
                processLocationReminder(state.locationReminderRequestedAt, state.locationReminderExpiresAt, "https")
            } else if (error != null) {
                publishLocalStatus("https_fallback_error")
            }
        }
    }

    private fun consumePendingFcmRefresh() {
        val prefs = getSharedPreferences("tracking_diag", MODE_PRIVATE)
        val persisted = prefs.getLong(KEY_PENDING_FCM_REFRESH_ID, 0L)
        val requestId = maxOf(pendingFcmRefreshRequestId, persisted)
        if (requestId <= 0L) return
        processRefreshRequest(requestId, "fcm")
    }

    private fun processRefreshRequest(ts: Long, transport: String) = journal { processRefreshInJournal(ts, transport) }
    private fun processRefreshInJournal(ts: Long, transport: String) {
        if (destroyed || ts <= 0L) return
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
        handler.post { if (!destroyed && activeRefreshId == ts) requestImmediate(ts) }
    }

    private fun resumeDurableRefresh() = journal {
        local.meta("refresh_pending")?.toLongOrNull()?.let { processRefreshRequest(it, "restart") }
        retryRefreshResult()
    }

    private fun saveRefreshResult(request: Long, fields: Map<String, Any?>) {
        val envelope = JSONObject().put("request", request).put("fields", JSONObject(fields.filterValues { it !is FieldValue }))
        try {
            local.finishRefresh(request, envelope.toString())
        } catch (e: Exception) { publishLocalStatus("refresh_store_failed:${e.javaClass.simpleName}"); return }
        activeRefreshId = 0L
        retryRefreshResult()
    }

    private fun retryRefreshResult() = journal { retryRefreshInJournal() }
    private fun retryRefreshInJournal() {
        if (destroyed || !cloudIdentityReady()) return
        val envelope = JSONObject(local.meta("refresh_result") ?: "{}")
        val request = envelope.optLong("request")
        val prefs = getSharedPreferences("tracking_diag", MODE_PRIVATE)
        if (request <= 0L || request <= prefs.getLong("refresh_delivered_for_v2210", 0L)) return
        val json = envelope.getJSONObject("fields")
        val fields = mutableMapOf<String, Any?>()
        json.keys().forEach { key -> fields[key] = if (json.isNull(key)) null else json.get(key) }
        fun confirmed() { prefs.edit().putLong("refresh_delivered_for_v2210", maxOf(request, prefs.getLong("refresh_delivered_for_v2210", 0L))).commit() }
        ChildDeviceWriter.write(fields) { ok -> if (ok) confirmed() }
    }

    private fun processLocationReminder(ts: Long, expiresAt: Long, transport: String) {
        if (ts <= lastLocationReminderSeen) return
        lastLocationReminderSeen = ts
        getSharedPreferences("tracking_diag", MODE_PRIVATE).edit().putLong("last_location_reminder_seen", ts).apply()
        val now = System.currentTimeMillis()
        publishLocalStatus("location_reminder_received_$transport")
        if (hasNotificationPermission()) ensureLocationReminderChannel()
        when {
            expiresAt > 0L && now > expiresAt -> publishLocationReminderAck(ts, "expired")
            isLocationEnabled() -> publishLocationReminderAck(ts, "already_on")
            !hasNotificationPermission() -> publishLocationReminderAck(ts, "notification_permission_missing")
            !isNotificationChannelEnabled(REMINDER_CHANNEL_ID) -> publishLocationReminderAck(ts, "notification_channel_disabled")
            else -> {
                showLocationReminderNotification(ts)
                publishLocationReminderAck(ts, "shown")
            }
        }
    }

    private fun publishLocationReminderAck(requestId: Long, result: String) {
        val now = System.currentTimeMillis()
        val payload = mapOf<String, Any?>(
            "locationReminderAckFor" to requestId,
            "locationReminderAckAt" to now,
            "locationReminderResult" to result
        )
        if (cloudIdentityReady()) {
            cloud.collection("devices").document(CHILD_DOC).set(payload, SetOptions.merge())
                .addOnFailureListener { e -> publishLocalStatus("reminder_ack:${e.javaClass.simpleName}") }
            ChildHttpsBridge.patch(payload)
        }
    }

    private fun showLocationReminderNotification(requestId: Long) {
        val channelId = REMINDER_CHANNEL_ID
        val nm = getSystemService(NotificationManager::class.java)
        ensureLocationReminderChannel()

        val openApp = Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_LOCATION_REMINDER, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openAppPi = PendingIntent.getActivity(
            this,
            (requestId and 0x7fffffff).toInt(),
            openApp,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val locationSettingsPi = PendingIntent.getActivity(
            this,
            ((requestId + 1) and 0x7fffffff).toInt(),
            Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_guardian_shield_notification)
            .setContentTitle("Bật vị trí để được bảo vệ an toàn")
            .setContentText("Chạm để mở cài đặt Vị trí.")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Bật vị trí để được bảo vệ an toàn. Chạm thông báo để mở cài đặt Vị trí."))
            .setContentIntent(locationSettingsPi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, "BẬT VỊ TRÍ", locationSettingsPi)
            .addAction(0, "MỞ ỨNG DỤNG", openAppPi)
            .build()
        nm.notify(LOCATION_REMINDER_NOTIFICATION_ID, notification)
    }

    private fun publishRefreshAck(requestId: Long) {
        if (!cloudIdentityReady()) return
        val now = System.currentTimeMillis()
        val payload = mapOf<String, Any?>(
            "refreshAckFor" to requestId,
            "refreshAckAt" to now,
            "refreshResult" to "locating",
            "refreshServiceFor" to requestId,
            "refreshServiceAt" to now,
            "refreshReceivedFor" to requestId,
            "refreshReceivedAt" to now
        )
        ChildDeviceWriter.write(payload) { ok -> if (ok) publishLocalStatus("refresh_ack_confirmed") }
    }

    private fun handleCandidate(location: Location, refreshFor: Long?) {
        if (Looper.myLooper() != journalThread.looper) { journal { handleCandidate(location, refreshFor) }; return }
        if (destroyed || (refreshFor != null && refreshFor != activeRefreshId)) return
        val problem = LocationPolicy.problem(location, lastAccepted, lastAcceptedGpsTime)
        if (problem != null) {
            if (refreshFor != null) publishRefreshFailure(refreshFor, problem)
            return
        }
        if (location.time < lastAcceptedGpsTime) {
            if (refreshFor != null) publishRefreshFailure(refreshFor, "Tọa độ GPS không mới hơn dữ liệu đã lưu")
            return
        }
        val previous = lastAccepted
        if (previous != null) {
            val jumpM = previous.distanceTo(location)
            val elapsedSec = ((location.time - previous.time) / 1000.0).coerceAtLeast(1.0)
            val impliedKmh = (jumpM / elapsedSec) * 3.6
            if (jumpM > 1_500 && elapsedSec < 600 && impliedKmh > 250 && location.accuracy >= previous.accuracy * 0.75f) {
                if (refreshFor != null) publishRefreshFailure(refreshFor, "Tọa độ mới không đáng tin cậy")
                return
            }
        }
        handle(location, refreshFor)
    }

    private fun usabilityProblem(location: Location): String? {
        if (!location.latitude.isFinite() || !location.longitude.isFinite() || location.latitude !in -90.0..90.0 || location.longitude !in -180.0..180.0) return "Tọa độ không hợp lệ"
        if (!location.accuracy.isFinite() || location.accuracy < 0f) return "Độ chính xác không hợp lệ"
        if (location.time <= 0L || location.time > System.currentTimeMillis() + 60_000L) return "Timestamp GPS không hợp lệ"
        if (location.latitude == 0.0 && location.longitude == 0.0) return "Tọa độ không hợp lệ"
        if (!location.hasAccuracy()) return "Không có thông tin độ chính xác"
        if (location.accuracy > MAX_ACCURACY_M) return "Độ chính xác GPS thấp (±${location.accuracy.toInt()} m)"
        val ageMs = ((SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000L).coerceAtLeast(0L)
        if (ageMs > MAX_LOCATION_AGE_MS) return "Tọa độ GPS quá cũ"
        return null
    }

    private fun handle(l: Location, refreshFor: Long?) {
        val now = System.currentTimeMillis()
        val sampleTime = trustedLocationTime(l, now)
        val sample = Sample(
            GeoPoint(l.latitude, l.longitude, l.accuracy.toDouble()),
            sampleTime,
            moving = (l.hasSpeed() && l.speed > 0.8f) || (lastAccepted?.distanceTo(l) ?: 0f) >= 35f
        )
        val before = engine.snapshot()
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
        RecoveryGeofenceManager.update(this, l)

        val diag = diagnosticFields(now)
        val payload = mutableMapOf<String, Any?>(
            "lastLat" to l.latitude,
            "lastLon" to l.longitude,
            "accuracy" to l.accuracy.toDouble(),
            "lastSeen" to sampleTime,
            "locationTime" to sampleTime,
            "lastSeenServer" to FieldValue.serverTimestamp(),
            "provider" to (l.provider ?: "fused"),
            "serviceState" to "tracking",
            "status" to "online",
            "lastError" to null
        )
        payload.putAll(diag)
        if (refreshFor != null) {
            payload["refreshCompletedFor"] = refreshFor
            payload["refreshCompletedAt"] = now
            payload["refreshResult"] = "ok"
        }
        if (refreshFor != null) {
            val persisted = mapOf<String, Any?>("refreshPersistedFor" to refreshFor, "refreshPersistedAt" to now)
            ChildDeviceWriter.write(persisted)
            payload["refreshPersistedFor"] = refreshFor; payload["refreshPersistedAt"] = now
            payload["refreshUploadedFor"] = refreshFor; payload["refreshUploadedAt"] = now
            saveRefreshResult(refreshFor, payload)
        }
        if (refreshFor == null && cloudIdentityReady()) {
            ChildDeviceWriter.write(payload) { ok ->
                if (ok && !destroyed) { failoverManager.reportServerSuccess(); publishLocalStatus("location_sent") }
            }
        }

        flushPending()
    }

    private fun eventJson(e: Event): JSONObject {
        val o = JSONObject()
        when (e) {
            is Event.VisitStarted -> {
                o.put("type", "visit_start"); o.put("id", e.visit.id); o.put("lat", e.visit.center.lat); o.put("lon", e.visit.center.lon)
                o.put("accuracy", e.visit.center.accuracyM); o.put("time", e.visit.arrivalMs)
            }
            is Event.VisitEnded -> { o.put("type", "visit_end"); o.put("id", e.visit.id); o.put("time", e.visit.departureMs) }
            is Event.TripStarted -> { o.put("type", "trip_start"); o.put("id", e.trip.id); o.put("time", e.trip.startMs) }
            is Event.TripPointAdded -> {
                o.put("eventId", "trip_point-${e.trip.id}-${EventIdentity.sampleId(e.sample.timeMs, e.sample.point.lat, e.sample.point.lon)}")
                o.put("type", "trip_point"); o.put("id", e.trip.id); o.put("lat", e.sample.point.lat); o.put("lon", e.sample.point.lon)
                o.put("accuracy", e.sample.point.accuracyM); o.put("time", e.sample.timeMs)
            }
            is Event.TripEnded -> { o.put("type", "trip_end"); o.put("id", e.trip.id); o.put("time", e.trip.endMs) }
        }
        return o
    }

    private fun queueNetworkEvent(type: String, eventTime: Long, offlineSince: Long = 0L, durationMs: Long = 0L) {
        val o = JSONObject()
            .put("type", type)
            .put("id", "$type-$eventTime")
            .put("time", eventTime)
        if (offlineSince > 0L) o.put("offlineSince", offlineSince)
        if (durationMs > 0L) o.put("durationMs", durationMs)
        journal {
            local.commitEventAndMeta(o.toString(), KEY_NETWORK_OFFLINE_SINCE, if (type == "network_offline") eventTime.toString() else "0")
            pendingEventCount = local.count(); flushPending()
        }
    }

    private fun flushPending() {
        if (!cloudIdentityReady() || !flushing.compareAndSet(false, true)) return
        background {
            pendingEventCount = try { local.batch(PENDING_REPORT_LIMIT).size } catch (_: Exception) { pendingEventCount }
            if (pendingEventCount > 0 && !syncSessionActive) {
                syncSessionActive = true
                syncSessionInitialPending = pendingEventCount
                syncSessionUploaded = 0
                publishSyncState("syncing")
            }
            uploadNext()
        }
    }

    private fun uploadNext() {
        val p = local.batch(1).firstOrNull()
        if (p == null) {
            pendingEventCount = 0
            flushing.set(false)
            if (syncSessionActive) {
                syncSessionActive = false
                publishSyncState("complete")
            }
            return
        }
        val o = try { JSONObject(p.json) } catch (e: Exception) {
            publishLocalStatus("event_json:${e.javaClass.simpleName}")
            flushing.set(false)
            publishSyncState("pending")
            return
        }
        val m = mutableMapOf<String, Any?>()
        o.keys().forEach { k -> m[k] = if (o.isNull(k)) null else o.get(k) }
        val documentId = EventIdentity.documentId(o)
        val resolved = AtomicBoolean(false)

        fun uploaded(transport: String) {
            if (!resolved.compareAndSet(false, true)) return
            background {
                local.delete(p.localId)
                pendingEventCount = (pendingEventCount - 1).coerceAtLeast(0)
                syncSessionUploaded++
                val progressNow = System.currentTimeMillis()
                if (progressNow - lastSyncProgressPublishedAt >= SYNC_PROGRESS_PUBLISH_MS) {
                    lastSyncProgressPublishedAt = progressNow
                    publishSyncState("syncing")
                }
                failoverManager.reportServerSuccess()
                publishLocalStatus("event_sent_$transport")
                uploadNext()
            }
        }

        val fallbackStarted = AtomicBoolean(false)
        fun fallback(reason: String) {
            if (destroyed || resolved.get() || !fallbackStarted.compareAndSet(false, true)) return
            ChildHttpsBridge.patchEvent(documentId, m) { ok, error ->
                if (ok) uploaded("https")
                else if (resolved.compareAndSet(false, true)) {
                    publishLocalStatus("event_pending_${reason}:${error ?: "unknown"}")
                    flushing.set(false)
                    publishSyncState("pending")
                }
            }
        }

        cloud.collection("devices").document(CHILD_DOC).collection("events")
            .document(documentId).set(m)
            .addOnSuccessListener { uploaded("firebase") }
            .addOnFailureListener { e -> fallback(e.javaClass.simpleName) }

        handler.postDelayed({ fallback("timeout") }, EVENT_FIRESTORE_TIMEOUT_MS)
    }

    private fun requestImmediate(refreshFor: Long?) {
        if (destroyed) return
        if (acquiring && refreshFor == null) return
        if (acquiring) acquisitionToken?.cancel()
        lastFreshFixAttemptElapsed = SystemClock.elapsedRealtime()
        val token = CancellationTokenSource()
        acquisitionToken = token
        acquiring = true
        if (refreshFor != null) {
            val progress = mapOf<String, Any?>("refreshLocatingFor" to refreshFor, "refreshLocatingAt" to System.currentTimeMillis())
            ChildDeviceWriter.write(progress)
        }
        fun finished() { if (acquisitionToken === token) { acquiring = false; acquisitionToken = null } }
        if (!hasFineLocation()) {
            finished()
            if (refreshFor != null) publishRefreshFailure(refreshFor, "Không có quyền vị trí chính xác")
            publishCloudStatus("permission_missing", "Không có quyền vị trí chính xác")
            return
        }
        if (!isLocationEnabled()) {
            finished()
            if (refreshFor != null) publishRefreshFailure(refreshFor, "Vị trí trên điện thoại đang tắt")
            publishCloudStatus("location_off", "Vị trí trên điện thoại đang tắt")
            return
        }
        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setMaxUpdateAgeMillis(0L)
            .setDurationMillis(25_000L)
            .build()
        try {
            client.getCurrentLocation(request, token.token)
                .addOnSuccessListener { l ->
                    if (destroyed || token.token.isCancellationRequested) return@addOnSuccessListener
                    finished()
                    if (l != null) handleCandidate(l, refreshFor)
                    else if (refreshFor != null) publishRefreshFailure(refreshFor, "Chưa lấy được GPS mới")
                    else publishCloudStatus("no_fix", "Chưa lấy được GPS mới")
                }
                .addOnFailureListener { e ->
                    if (destroyed || token.token.isCancellationRequested) return@addOnFailureListener
                    finished()
                    if (refreshFor != null) publishRefreshFailure(refreshFor, "GPS lỗi: ${e.javaClass.simpleName}")
                    else publishCloudStatus("location_error", e.javaClass.simpleName)
                }
        } catch (e: SecurityException) {
            finished()
            if (refreshFor != null) publishRefreshFailure(refreshFor, "Không đủ quyền vị trí")
            publishLocalStatus("current_security:${e.javaClass.simpleName}")
        }
    }

    private fun publishRefreshFailure(requestId: Long, message: String) {
        if (Looper.myLooper() != journalThread.looper) { journal { publishRefreshFailure(requestId, message) }; return }
        if (destroyed || (activeRefreshId > 0L && requestId != activeRefreshId)) return
        val now = System.currentTimeMillis()
        val payload = mutableMapOf<String, Any?>(
            "refreshFailedFor" to requestId,
            "refreshFailedAt" to now,
            "refreshResult" to "failed",
            "lastError" to message
        )
        payload.putAll(diagnosticFields(now))
        saveRefreshResult(requestId, payload)
    }

    private fun publishHeartbeat() {
        val now = System.currentTimeMillis()
        lastCloudHeartbeatAttemptAt = now
        maintainTrackingNotification(now)
        updateNetworkHistory(now)
        if (!cloudIdentityReady()) return
        val service = if (hasFineLocation() && isLocationEnabled()) "tracking" else "attention_needed"
        val data = mutableMapOf<String, Any?>(
            "heartbeatAt" to now,
            "heartbeatServer" to FieldValue.serverTimestamp(),
            "serviceState" to service,
            "status" to "online",
            "lastCloudHeartbeatAttemptAt" to now
        )
        data.putAll(diagnosticFields(now))
        val start = SystemClock.elapsedRealtime()
        cloud.collection("devices").document(CHILD_DOC).set(data, SetOptions.merge())
            .addOnSuccessListener {
                val latency = (SystemClock.elapsedRealtime() - start).coerceAtLeast(0L)
                lastCloudHeartbeatSuccessAt = System.currentTimeMillis()
                getSharedPreferences("tracking_diag", MODE_PRIVATE).edit().putLong("last_cloud_heartbeat_success_v2210", lastCloudHeartbeatSuccessAt).apply()
                failoverManager.reportServerSuccess()
                publishLocalStatus("heartbeat_firebase")
                ChildHttpsBridge.patch(mapOf("firebaseWriteOkAt" to System.currentTimeMillis(), "firebaseLatencyMs" to latency))
            }
            .addOnFailureListener { e -> publishLocalStatus("heartbeat_write:${e.javaClass.simpleName}") }

        val rest = data.filterValues { it !is FieldValue }
        ChildHttpsBridge.patch(rest) { ok, _ -> if (ok) {
            lastCloudHeartbeatSuccessAt = System.currentTimeMillis()
            getSharedPreferences("tracking_diag", MODE_PRIVATE).edit().putLong("last_cloud_heartbeat_success_v2210", lastCloudHeartbeatSuccessAt).apply()
            failoverManager.reportServerSuccess()
            publishLocalStatus("heartbeat_https")
        } }
    }

    private fun publishCloudStatus(status: String, error: String?) {
        if (!cloudIdentityReady()) return
        val now = System.currentTimeMillis()
        val data = mutableMapOf<String, Any?>(
            "status" to status,
            "statusAt" to now,
            "serviceState" to status,
            "lastError" to error
        )
        data.putAll(diagnosticFields(now))
        cloud.collection("devices").document(CHILD_DOC).set(data, SetOptions.merge())
            .addOnFailureListener { e -> publishLocalStatus("status_write:${e.javaClass.simpleName}") }
        val rest = data.toMutableMap().apply { if (error == null) this["lastError"] = "" }
        ChildHttpsBridge.patch(rest)
    }

    private fun diagnosticFields(now: Long): MutableMap<String, Any?> {
        val network = networkState()
        val locationEnabled = isLocationEnabled()
        val locationOffSince = updateLocationOffSince(now, locationEnabled)
        val failover = failoverManager.snapshot()
        return mutableMapOf(
            "diagnosticAt" to now,
            "locationEnabled" to locationEnabled,
            "locationOffSince" to locationOffSince,
            "fineLocationGranted" to hasFineLocation(),
            "backgroundLocationGranted" to hasBackgroundLocation(),
            "notificationGranted" to hasNotificationPermission(),
            "networkType" to network.first,
            "networkValidated" to network.second,
            "networkAt" to now,
            "serviceStartedAt" to serviceStartedAt,
            "appVersion" to BuildConfig.VERSION_NAME,
            "httpsLatencyMs" to ChildHttpsBridge.lastLatencyMs.coerceAtLeast(0L),
            "pendingEventCount" to pendingEventCount,
            "syncState" to if (syncSessionActive) "syncing" else if (pendingEventCount > 0) "pending" else "complete",
            "networkOfflineSince" to getSharedPreferences("tracking_diag", MODE_PRIVATE).getLong(KEY_NETWORK_OFFLINE_SINCE, 0L),
            "lastOfflineStartAt" to getSharedPreferences("tracking_diag", MODE_PRIVATE).getLong(KEY_LAST_OFFLINE_START, 0L),
            "lastOfflineEndAt" to getSharedPreferences("tracking_diag", MODE_PRIVATE).getLong(KEY_LAST_OFFLINE_END, 0L),
            "lastOfflineDurationMs" to getSharedPreferences("tracking_diag", MODE_PRIVATE).getLong(KEY_LAST_OFFLINE_DURATION, 0L),
            "syncCompletedAt" to getSharedPreferences("tracking_diag", MODE_PRIVATE).getLong(KEY_SYNC_COMPLETED_AT, 0L),
            "syncUploadedCount" to getSharedPreferences("tracking_diag", MODE_PRIVATE).getInt(KEY_SYNC_UPLOADED_COUNT, 0),
            "routeMode" to failover.routeMode,
            "cellularFailoverActive" to failover.cellularFailoverActive,
            "cellularFailoverSince" to failover.cellularFailoverSince,
            "wifiServerProbeOkAt" to failover.wifiServerProbeOkAt,
            "wifiServerProbeFailAt" to failover.wifiServerProbeFailAt,
            "wifiServerFailureCount" to failover.wifiServerFailureCount,
            "actualServerFailureCount" to failover.actualServerFailureCount,
            "cellularAvailable" to failover.cellularAvailable,
            "serviceInstanceId" to serviceInstanceId,
            "serviceStartCount" to serviceStartCount,
            "serviceRestartCount" to (serviceStartCount - 1).coerceAtLeast(0),
            "lastLocalServiceHeartbeatAt" to lastLocalServiceHeartbeatAt,
            "lastGpsCallbackAt" to lastGpsCallbackAt,
            "lastCloudHeartbeatAttemptAt" to lastCloudHeartbeatAttemptAt,
            "lastCloudHeartbeatSuccessAt" to getSharedPreferences("tracking_diag", MODE_PRIVATE).getLong("last_cloud_heartbeat_success_v2210", 0L),
            "processExitReason" to getSharedPreferences("tracking_diag", MODE_PRIVATE).getInt("last_process_exit_reason", 0),
            "processExitImportance" to getSharedPreferences("tracking_diag", MODE_PRIVATE).getInt("last_process_exit_importance_v2210", 0),
            "pendingJourneyCount" to pendingEventCount,
            "geofenceRegisteredAt" to getSharedPreferences("tracking_diag", MODE_PRIVATE).getLong("recovery_geofence_registered_at_v229", 0L),
            "geofenceTriggeredAt" to getSharedPreferences("tracking_diag", MODE_PRIVATE).getLong("recovery_geofence_event_at_v229", 0L),
            "fcmTokenConfirmedAt" to getSharedPreferences("tracking_diag", MODE_PRIVATE).getLong("fcm_token_cloud_at", 0L),
            "fcmOriginalPriority" to getSharedPreferences("tracking_diag", MODE_PRIVATE).getInt("fcm_wake_original_priority_v229", 0),
            "protectionChannelEnabled" to isNotificationChannelEnabled(PROTECTION_CHANNEL_ID),
            "wakeNotificationEnabled" to getSharedPreferences("tracking_diag", MODE_PRIVATE).getBoolean("protection_notification_enabled_v229", false),
            "unusedAppRestricted" to unusedRestrictionEnabled(),
            "unusedAppRestrictionMeaning" to "auto_reset_enabled_not_current_hibernation",
            "fcmDeliveredPriority" to getSharedPreferences("tracking_diag", MODE_PRIVATE).getInt("fcm_wake_priority", 0),
            "batteryOptimizationIgnored" to isBatteryOptimizationIgnored(),
            "backgroundRestricted" to isBackgroundRestricted(),
            "appStandbyRestricted" to isAppStandbyRestricted(),
            "watchdogLastRunAt" to getSharedPreferences("tracking_diag", MODE_PRIVATE).getLong(ServiceWatchdogWorker.KEY_WATCHDOG_LAST_RUN_AT, 0L),
            "watchdogResult" to getSharedPreferences("tracking_diag", MODE_PRIVATE).getString(ServiceWatchdogWorker.KEY_WATCHDOG_RESULT, "unknown"),
            "watchdogTrigger" to getSharedPreferences("tracking_diag", MODE_PRIVATE).getString(ServiceWatchdogWorker.KEY_WATCHDOG_TRIGGER, "unknown"),
            "lastProcessExitReason" to getSharedPreferences("tracking_diag", MODE_PRIVATE).getInt("last_process_exit_reason", 0),
            "lastProcessExitAt" to getSharedPreferences("tracking_diag", MODE_PRIVATE).getLong("last_process_exit_at", 0L),
            "lastTrimMemoryLevel" to getSharedPreferences("tracking_diag", MODE_PRIVATE).getInt("last_trim_memory_level", 0),
            "serviceTaskRemovedAt" to getSharedPreferences("tracking_diag", MODE_PRIVATE).getLong("service_task_removed_at", 0L),
            "serviceDestroyedAt" to getSharedPreferences("tracking_diag", MODE_PRIVATE).getLong("service_destroyed_at", 0L),
            "fcmWakeReceivedAt" to getSharedPreferences("tracking_diag", MODE_PRIVATE).getLong("fcm_wake_received_at", 0L),
            "fcmWakeRequestId" to getSharedPreferences("tracking_diag", MODE_PRIVATE).getLong("fcm_wake_request_id", 0L),
            "fcmWakeStartResult" to getSharedPreferences("tracking_diag", MODE_PRIVATE).getString("fcm_wake_start_result", "unknown"),
            "fcmTokenCloudAt" to getSharedPreferences("tracking_diag", MODE_PRIVATE).getLong("fcm_token_cloud_at", 0L),
            "fcmWakeReady" to getSharedPreferences("tracking_diag", MODE_PRIVATE).getBoolean("fcm_wake_ready_v229", false),
            "fcmDeliveredHigh" to getSharedPreferences("tracking_diag", MODE_PRIVATE).getBoolean("fcm_wake_delivered_high_v229", false),
            "fcmOriginalHigh" to getSharedPreferences("tracking_diag", MODE_PRIVATE).getBoolean("fcm_wake_original_high_v229", false),
            "unusedAppRestrictionsStatus" to getSharedPreferences("tracking_diag", MODE_PRIVATE).getInt("unused_app_restrictions_status_v229", 0),
            "unusedAppRestrictionsEnabled" to getSharedPreferences("tracking_diag", MODE_PRIVATE).getBoolean("unused_app_restrictions_enabled_v229", true),
            "recoveryGeofenceResult" to getSharedPreferences("tracking_diag", MODE_PRIVATE).getString("recovery_geofence_result_v229", "unknown"),
            "recoveryGeofenceRegisteredAt" to getSharedPreferences("tracking_diag", MODE_PRIVATE).getLong("recovery_geofence_registered_at_v229", 0L),
            "recoveryGeofenceEventAt" to getSharedPreferences("tracking_diag", MODE_PRIVATE).getLong("recovery_geofence_event_at_v229", 0L),
            "lastRecoveryReason" to getSharedPreferences("tracking_diag", MODE_PRIVATE).getString("last_recovery_reason_v229", "unknown")
        )
    }

    private fun updateLocationOffSince(now: Long, locationEnabled: Boolean): Long {
        val prefs = getSharedPreferences("tracking_diag", MODE_PRIVATE)
        val existing = prefs.getLong(KEY_LOCATION_OFF_SINCE, 0L)
        if (locationEnabled) {
            if (existing != 0L) prefs.edit().remove(KEY_LOCATION_OFF_SINCE).apply()
            return 0L
        }
        if (existing in 1..now) return existing
        prefs.edit().putLong(KEY_LOCATION_OFF_SINCE, now).apply()
        return now
    }

    private fun maintainTrackingNotification(now: Long) {
        val prefs = getSharedPreferences("tracking_diag", MODE_PRIVATE)
        val dismissedAt = prefs.getLong(KEY_TRACKING_NOTIFICATION_DISMISSED_AT, 0L)
        if (dismissedAt <= 0L || now - dismissedAt < TRACKING_NOTIFICATION_RESHOW_MS) return
        if (!isNotificationChannelEnabled(PROTECTION_CHANNEL_ID)) return
        getSystemService(NotificationManager::class.java).notify(TRACKING_NOTIFICATION_ID, trackingNotification())
        prefs.edit().remove(KEY_TRACKING_NOTIFICATION_DISMISSED_AT).apply()
        publishLocalStatus("tracking_notification_restored")
    }

    private fun writeLocalServiceHeartbeat(now: Long) {
        lastLocalServiceHeartbeatAt = now
        getSharedPreferences("tracking_diag", MODE_PRIVATE).edit()
            .putLong(ServiceWatchdogWorker.KEY_LAST_LOCAL_HEARTBEAT_AT, now)
            .putString(KEY_SERVICE_INSTANCE_ID, serviceInstanceId)
            .putBoolean(ServiceWatchdogWorker.KEY_BATTERY_OPTIMIZATION_IGNORED, isBatteryOptimizationIgnored())
            .putBoolean(ServiceWatchdogWorker.KEY_BACKGROUND_RESTRICTED, isBackgroundRestricted())
            .apply()
    }

    private fun unusedRestrictionEnabled(): Boolean? {
        val p = getSharedPreferences("tracking_diag", MODE_PRIVATE)
        if (!p.contains("unused_app_restrictions_checked_at_v229")) return null
        val status = p.getInt("unused_app_restrictions_status_v229", androidx.core.content.UnusedAppRestrictionsConstants.ERROR)
        if (status == androidx.core.content.UnusedAppRestrictionsConstants.ERROR) return null
        return p.getBoolean("unused_app_restrictions_enabled_v229", false)
    }

    private fun isBatteryOptimizationIgnored(): Boolean = try {
        Build.VERSION.SDK_INT < 23 || getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
    } catch (_: Exception) { false }

    private fun isBackgroundRestricted(): Boolean = try {
        Build.VERSION.SDK_INT >= 28 && getSystemService(ActivityManager::class.java).isBackgroundRestricted
    } catch (_: Exception) { false }

    private fun isAppStandbyRestricted(): Boolean = try {
        Build.VERSION.SDK_INT >= 30 && getSystemService(UsageStatsManager::class.java).appStandbyBucket == UsageStatsManager.STANDBY_BUCKET_RESTRICTED
    } catch (_: Exception) { false }

    private fun restartCloudAfterRouteChange() {
        if (destroyed) return
        val generation = ++routeGeneration
        ++listenerGeneration
        authRetryAt = 0L
        authInFlight = false
        ensureAuthenticated()
        WakeTokenSyncWorker.schedule(this)
        commandListener?.remove()
        commandListener = null
        try {
            cloud.disableNetwork().addOnCompleteListener {
                if (destroyed || generation != routeGeneration) return@addOnCompleteListener
                cloud.enableNetwork().addOnCompleteListener {
                    if (destroyed || generation != routeGeneration) return@addOnCompleteListener
                    if (cloudIdentityReady()) {
                        listenCommands()
                        pollRestCommand()
                        publishHeartbeat()
                        flushPending()
                        if (hasFineLocation() && isLocationEnabled()) requestImmediate(null)
                    }
                }
            }
        } catch (e: Exception) {
            publishLocalStatus("network_route_restart:${e.javaClass.simpleName}")
        }
    }

    private fun publishFailoverState(reason: String) {
        if (!cloudIdentityReady()) return
        val s = failoverManager.snapshot()
        val payload = mapOf<String, Any?>(
            "routeMode" to s.routeMode,
            "cellularFailoverActive" to s.cellularFailoverActive,
            "cellularFailoverSince" to s.cellularFailoverSince,
            "wifiServerProbeOkAt" to s.wifiServerProbeOkAt,
            "wifiServerProbeFailAt" to s.wifiServerProbeFailAt,
            "wifiServerFailureCount" to s.wifiServerFailureCount,
            "cellularAvailable" to s.cellularAvailable,
            "routeChangedAt" to System.currentTimeMillis(),
            "routeReason" to reason
        )
        cloud.collection("devices").document(CHILD_DOC).set(payload, SetOptions.merge())
            .addOnFailureListener { e -> publishLocalStatus("route_state:${e.javaClass.simpleName}") }
        ChildHttpsBridge.patch(payload)
    }

    private fun trustedLocationTime(location: Location, now: Long): Long = location.time // validated original GPS timestamp

    private fun updateNetworkHistory(now: Long) = journal { updateNetworkHistoryInJournal(now) }
    private fun updateNetworkHistoryInJournal(now: Long) {
        val prefs = getSharedPreferences("tracking_diag", MODE_PRIVATE)
        val validated = networkState().second
        val offlineSince = local.meta(KEY_NETWORK_OFFLINE_SINCE)?.toLongOrNull() ?: prefs.getLong(KEY_NETWORK_OFFLINE_SINCE, 0L)
        if (!validated) {
            if (offlineSince <= 0L) {
                queueNetworkEvent("network_offline", now)
                prefs.edit().putLong(KEY_NETWORK_OFFLINE_SINCE, now).apply()
                publishLocalStatus("network_offline_recorded")
            }
            return
        }
        if (offlineSince > 0L && offlineSince <= now) {
            val duration = now - offlineSince
            queueNetworkEvent("network_restored", now, offlineSince, duration)
            prefs.edit()
                .remove(KEY_NETWORK_OFFLINE_SINCE)
                .putLong(KEY_LAST_OFFLINE_START, offlineSince)
                .putLong(KEY_LAST_OFFLINE_END, now)
                .putLong(KEY_LAST_OFFLINE_DURATION, duration)
                .apply()
            publishLocalStatus("network_restored_recorded")
        }
    }

    private fun publishSyncState(stateName: String) {
        val now = System.currentTimeMillis()
        val prefs = getSharedPreferences("tracking_diag", MODE_PRIVATE)
        if (stateName == "complete") {
            prefs.edit()
                .putLong(KEY_SYNC_COMPLETED_AT, now)
                .putInt(KEY_SYNC_UPLOADED_COUNT, syncSessionUploaded)
                .apply()
        }
        val payload = mapOf<String, Any?>(
            "syncState" to stateName,
            "syncPendingCount" to pendingEventCount,
            "syncStartedAt" to if (syncSessionActive) now else 0L,
            "syncCompletedAt" to prefs.getLong(KEY_SYNC_COMPLETED_AT, 0L),
            "syncUploadedCount" to syncSessionUploaded,
            "lastOfflineStartAt" to prefs.getLong(KEY_LAST_OFFLINE_START, 0L),
            "lastOfflineEndAt" to prefs.getLong(KEY_LAST_OFFLINE_END, 0L),
            "lastOfflineDurationMs" to prefs.getLong(KEY_LAST_OFFLINE_DURATION, 0L)
        )
        if (cloudIdentityReady()) {
            cloud.collection("devices").document(CHILD_DOC).set(payload, SetOptions.merge())
                .addOnFailureListener { e -> publishLocalStatus("sync_state:${e.javaClass.simpleName}") }
            ChildHttpsBridge.patch(payload)
        }
    }

    private fun networkState(): Pair<String, Boolean> {
        return try {
            val cm = getSystemService(ConnectivityManager::class.java)
            val active = cm.activeNetwork ?: return "none" to false
            val caps = cm.getNetworkCapabilities(active) ?: return "unknown" to false
            val type = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
                else -> "other"
            }
            type to caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (_: Exception) {
            "unknown" to false
        }
    }

    private fun hasFineLocation(): Boolean =
        ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun hasBackgroundLocation(): Boolean =
        Build.VERSION.SDK_INT < 29 || ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        return try {
            val nm = getSystemService(NotificationManager::class.java)
            Build.VERSION.SDK_INT < 24 || nm.areNotificationsEnabled()
        } catch (_: Exception) {
            true
        }
    }

    private fun isNotificationChannelEnabled(channelId: String): Boolean {
        if (!hasNotificationPermission()) return false
        if (Build.VERSION.SDK_INT < 26) return true
        return try {
            val channel = getSystemService(NotificationManager::class.java).getNotificationChannel(channelId)
            channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
        } catch (_: Exception) {
            true
        }
    }

    private fun ensureLocationReminderChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(REMINDER_CHANNEL_ID, "Lời nhắc từ gia đình", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Thông báo được gửi khi gia đình chủ động nhắc bật Vị trí"
            }
        )
    }

    private fun isLocationEnabled(): Boolean = try {
        androidx.core.location.LocationManagerCompat.isLocationEnabled(getSystemService(LocationManager::class.java))
    } catch (_: Exception) {
        false
    }

    private fun publishLocalStatus(status: String) {
        val probeFailureHint = status.startsWith("heartbeat_write:") ||
            status.startsWith("device_write:") ||
            status.startsWith("command_listener:") ||
            status.startsWith("auth_error:") ||
            status.startsWith("https_fallback_error") ||
            status.startsWith("event_pending_")
        if (probeFailureHint && ::failoverManager.isInitialized) failoverManager.reportServerFailure()
        getSharedPreferences("tracking_diag", MODE_PRIVATE).edit()
            .putString("status", status)
            .putLong("time", System.currentTimeMillis())
            .apply()
    }

    override fun onDestroy() {
        destroyed = true
        running = false
        acquisitionToken?.cancel()
        acquiring = false
        handler.removeCallbacksAndMessages(null)
        ++listenerGeneration; ++routeGeneration
        commandListener?.remove()
        handler.removeCallbacks(localServiceHeartbeat)
        handler.removeCallbacks(heartbeat)
        handler.removeCallbacks(fallbackPoll)
        handler.removeCallbacks(healthCheck)
        handler.removeCallbacks(networkRecovery)
        handler.removeCallbacks(networkHistoryCheck)
        if (::failoverManager.isInitialized) failoverManager.stop()
        getSharedPreferences("tracking_diag", MODE_PRIVATE).edit()
            .putLong(KEY_SERVICE_DESTROYED_AT, System.currentTimeMillis())
            .apply()
        if (trackingActive) client.removeLocationUpdates(callback)
        if (networkCallbackRegistered) {
            try { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback) } catch (_: Exception) {}
        }
        journalThread.quitSafely()
        io.shutdown()
        super.onDestroy()
        getSharedPreferences("tracking_diag", MODE_PRIVATE).edit()
            .putLong("service_destroyed_at", System.currentTimeMillis())
            .apply()
        ServiceWatchdogWorker.requestImmediateRecovery(this, "service_destroyed")
    }

    companion object {
        @Volatile var running: Boolean = false
            private set
        const val EXTRA_FCM_REFRESH_REQUEST_ID = "fcm_refresh_request_id"
        private const val KEY_PENDING_FCM_REFRESH_ID = "pending_fcm_refresh_request_id"
        private const val REFRESH_TTL_MS = 15 * 60_000L
        private const val CHILD_DOC = "child-01"
        private const val TRACKING_NOTIFICATION_ID = 42
        private const val LOCATION_REMINDER_NOTIFICATION_ID = 77
        private const val PROTECTION_CHANNEL_ID = "protection"
        private const val REMINDER_CHANNEL_ID = "family_location_reminder"
        private const val TRACK_INTERVAL_MS = 60_000L
        private const val TRACK_FASTEST_MS = 30_000L
        private const val TRACK_MAX_DELAY_MS = 120_000L
        private const val TRACK_DISTANCE_M = 20f
        private const val SAMPLE_DISTANCE_M = 50f
        private const val SAMPLE_MAX_INTERVAL_MS = 10 * 60_000L
        private const val HEARTBEAT_MS = 5 * 60_000L
        private const val FALLBACK_POLL_MS = 15_000L
        private const val HEALTH_CHECK_MS = 30_000L
        private const val NETWORK_RECOVERY_DEBOUNCE_MS = 1_500L
        private const val NETWORK_LOSS_CONFIRM_MS = 2_500L
        private const val EVENT_FIRESTORE_TIMEOUT_MS = 12_000L
        private const val SYNC_PROGRESS_PUBLISH_MS = 10_000L
        private const val PENDING_REPORT_LIMIT = 5_000
        private const val TRUSTED_LOCATION_TIME_MAX_AGE_MS = 10 * 60_000L
        private const val KEY_NETWORK_OFFLINE_SINCE = "network_offline_since"
        private const val KEY_LAST_OFFLINE_START = "last_offline_start"
        private const val KEY_LAST_OFFLINE_END = "last_offline_end"
        private const val KEY_LAST_OFFLINE_DURATION = "last_offline_duration"
        private const val KEY_SYNC_COMPLETED_AT = "sync_completed_at"
        private const val KEY_SYNC_UPLOADED_COUNT = "sync_uploaded_count"
        private const val LOCAL_SERVICE_HEARTBEAT_MS = 60_000L
        private const val KEY_SERVICE_START_COUNT = "service_start_count"
        private const val KEY_SERVICE_INSTANCE_ID = "service_instance_id"
        private const val KEY_SERVICE_STARTED_AT_LOCAL = "service_started_at_local"
        private const val KEY_SERVICE_DESTROYED_AT = "service_destroyed_at"
        private const val FIREBASE_HEALTH_THROTTLE_MS = 60_000L
        private const val TRACKING_NOTIFICATION_RESHOW_MS = 8 * 60 * 60_000L
        private const val ACTION_TRACKING_NOTIFICATION_DISMISSED = "com.family.child.TRACKING_NOTIFICATION_DISMISSED"
        private const val KEY_TRACKING_NOTIFICATION_DISMISSED_AT = "tracking_notification_dismissed_at"
        private const val KEY_LOCATION_OFF_SINCE = "location_off_since"
        private const val MAX_ACCURACY_M = 150f
        private const val MAX_LOCATION_AGE_MS = 120_000L
    }
}
