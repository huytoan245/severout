package com.family.child

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.family.core.*
import com.google.android.gms.location.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.SetOptions
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class LocationService : Service() {
    private lateinit var client: FusedLocationProviderClient
    private val engine = VisitEngine()
    private val cloud by lazy { FirebaseFirestore.getInstance() }
    private val local by lazy { PendingStore(this) }
    private val io = Executors.newSingleThreadExecutor()
    private val flushing = AtomicBoolean(false)
    private lateinit var state: StateStore
    private var commandListener: ListenerRegistration? = null
    private var lastRefreshSeen = 0L
    private var lastAccepted: Location? = null
    private var lastSamplePersisted: Location? = null
    private var lastSamplePersistedAt = 0L
    private var trackingActive = false
    private var lastHealthSignature = ""
    private var networkCallbackRegistered = false
    private val handler = Handler(Looper.getMainLooper())

    private val heartbeat = object : Runnable {
        override fun run() {
            publishHeartbeat()
            handler.postDelayed(this, HEARTBEAT_MS)
        }
    }

    private val fallbackPoll = object : Runnable {
        override fun run() {
            pollRestCommand()
            handler.postDelayed(this, FALLBACK_POLL_MS)
        }
    }

    private val healthCheck = object : Runnable {
        override fun run() {
            evaluateTrackingHealth()
            handler.postDelayed(this, HEALTH_CHECK_MS)
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            handler.post {
                publishLocalStatus("network_available")
                cloud.enableNetwork()
                if (FirebaseAuth.getInstance().currentUser != null) {
                    listenCommands()
                    pollRestCommand()
                    publishHeartbeat()
                    flushPending()
                    if (hasFineLocation() && isLocationEnabled()) requestImmediate(null)
                }
            }
        }

        override fun onLost(network: Network) {
            publishLocalStatus("network_lost")
        }
    }

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach { handleCandidate(it, null) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        client = LocationServices.getFusedLocationProviderClient(this)
        state = StateStore(this)
        state.restore(engine)
        lastRefreshSeen = getSharedPreferences("tracking_diag", MODE_PRIVATE).getLong("last_refresh_seen", 0L)
        startAsForeground()
        registerNetworkRecovery()
        publishLocalStatus("starting")

        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser != null) {
            onAuthenticated()
        } else {
            auth.signInAnonymously()
                .addOnSuccessListener { onAuthenticated() }
                .addOnFailureListener { e -> publishLocalStatus("auth_error:${e.javaClass.simpleName}") }
        }

        requestTracking()
        handler.postDelayed(heartbeat, HEARTBEAT_MS)
        handler.postDelayed(fallbackPoll, 3_000L)
        handler.postDelayed(healthCheck, 10_000L)
    }

    private fun onAuthenticated() {
        publishLocalStatus("auth_ok")
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
        val n = notification()
        if (Build.VERSION.SDK_INT >= 29) ServiceCompat.startForeground(this, 42, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        else startForeground(42, n)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getBooleanExtra("immediate", false) == true) {
            evaluateTrackingHealth()
            requestImmediate(null)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(): Notification {
        val id = "protection"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(id, "Protection", NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        })
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, id)
            .setSmallIcon(R.drawable.ic_guardian_shield_notification)
            .setContentTitle("Điện thoại của bạn đang được bảo vệ an toàn")
            .setContentIntent(pi)
            .setOngoing(true)
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
            client.requestLocationUpdates(request, callback, mainLooper)
            trackingActive = true
            publishLocalStatus("tracking")
        } catch (e: SecurityException) {
            trackingActive = false
            publishLocalStatus("tracking_security:${e.javaClass.simpleName}")
        }
    }

    private fun evaluateTrackingHealth() {
        val fine = hasFineLocation()
        val locationOn = isLocationEnabled()
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

        if (signature != lastHealthSignature) {
            lastHealthSignature = signature
            when {
                !fine -> publishCloudStatus("permission_missing", "Không có quyền vị trí chính xác")
                !locationOn -> publishCloudStatus("location_off", "Vị trí trên điện thoại đang tắt")
                else -> publishCloudStatus("tracking", null)
            }
        }
    }

    private fun listenCommands() {
        commandListener?.remove()
        commandListener = cloud.collection("devices").document(CHILD_DOC)
            .addSnapshotListener(MetadataChanges.INCLUDE) { d, error ->
                if (error != null) {
                    publishLocalStatus("command_listener:${error.code}")
                    return@addSnapshotListener
                }
                if (d == null || !d.exists()) return@addSnapshotListener
                if (!d.metadata.isFromCache) publishLocalStatus("firebase_realtime_ok")
                processRefreshRequest(d.getLong("refreshRequestedAt") ?: 0L, "firebase")
            }
    }

    private fun pollRestCommand() {
        if (FirebaseAuth.getInstance().currentUser == null) return
        ChildHttpsBridge.readDevice { state, error ->
            if (state != null) {
                publishLocalStatus("https_fallback_ok")
                processRefreshRequest(state.refreshRequestedAt, "https")
            } else if (error != null) {
                publishLocalStatus("https_fallback_error")
            }
        }
    }

    private fun processRefreshRequest(ts: Long, transport: String) {
        if (ts <= lastRefreshSeen) return
        lastRefreshSeen = ts
        getSharedPreferences("tracking_diag", MODE_PRIVATE).edit().putLong("last_refresh_seen", ts).apply()
        publishLocalStatus("refresh_received_$transport")
        publishRefreshAck(ts)
        requestImmediate(ts)
    }

    private fun publishRefreshAck(requestId: Long) {
        if (FirebaseAuth.getInstance().currentUser == null) return
        val now = System.currentTimeMillis()
        val payload = mapOf<String, Any?>(
            "refreshAckFor" to requestId,
            "refreshAckAt" to now,
            "refreshResult" to "locating"
        )
        cloud.collection("devices").document(CHILD_DOC).set(payload, SetOptions.merge())
            .addOnFailureListener { e -> publishLocalStatus("refresh_ack:${e.javaClass.simpleName}") }
        ChildHttpsBridge.patch(payload) { ok, _ -> if (ok) publishLocalStatus("refresh_ack_https") }
    }

    private fun handleCandidate(location: Location, refreshFor: Long?) {
        val problem = usabilityProblem(location)
        if (problem != null) {
            if (refreshFor != null) publishRefreshFailure(refreshFor, problem)
            return
        }
        val previous = lastAccepted
        if (previous != null) {
            val jumpM = previous.distanceTo(location)
            val elapsedSec = ((location.elapsedRealtimeNanos - previous.elapsedRealtimeNanos) / 1_000_000_000.0).coerceAtLeast(1.0)
            val impliedKmh = (jumpM / elapsedSec) * 3.6
            if (jumpM > 10_000 && elapsedSec < 600 && impliedKmh > 220 && location.accuracy >= previous.accuracy * 0.75f) {
                if (refreshFor != null) publishRefreshFailure(refreshFor, "Tọa độ mới không đáng tin cậy")
                return
            }
        }
        lastAccepted = Location(location)
        handle(location, refreshFor)
    }

    private fun usabilityProblem(location: Location): String? {
        if (location.latitude == 0.0 && location.longitude == 0.0) return "Tọa độ không hợp lệ"
        if (!location.hasAccuracy()) return "Không có thông tin độ chính xác"
        if (location.accuracy > MAX_ACCURACY_M) return "Độ chính xác GPS thấp (±${location.accuracy.toInt()} m)"
        val ageMs = ((SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000L).coerceAtLeast(0L)
        if (ageMs > MAX_LOCATION_AGE_MS) return "Tọa độ GPS quá cũ"
        return null
    }

    private fun handle(l: Location, refreshFor: Long?) {
        val now = System.currentTimeMillis()
        val sample = Sample(
            GeoPoint(l.latitude, l.longitude, l.accuracy.toDouble()),
            now,
            moving = (l.hasSpeed() && l.speed > 0.8f) || (lastSamplePersisted?.distanceTo(l) ?: 0f) >= 35f
        )
        val events = if (engine.currentVisit() == null && engine.currentTrip() == null) engine.seedVisit(sample) else engine.accept(sample)
        state.save(engine)
        events.forEach(::queueEvent)

        if (FirebaseAuth.getInstance().currentUser != null) {
            val payload = mutableMapOf<String, Any?>(
                "lastLat" to l.latitude,
                "lastLon" to l.longitude,
                "accuracy" to l.accuracy.toDouble(),
                "lastSeen" to now,
                "lastSeenServer" to FieldValue.serverTimestamp(),
                "provider" to (l.provider ?: "fused"),
                "locationEnabled" to isLocationEnabled(),
                "fineLocationGranted" to hasFineLocation(),
                "backgroundLocationGranted" to hasBackgroundLocation(),
                "serviceState" to "tracking",
                "status" to "online",
                "lastError" to null
            )
            if (refreshFor != null) {
                payload["refreshCompletedFor"] = refreshFor
                payload["refreshCompletedAt"] = now
                payload["refreshResult"] = "ok"
            }
            cloud.collection("devices").document(CHILD_DOC).set(payload, SetOptions.merge())
                .addOnSuccessListener { publishLocalStatus("location_sent") }
                .addOnFailureListener { e -> publishLocalStatus("device_write:${e.javaClass.simpleName}") }

            val restPayload = mutableMapOf<String, Any?>(
                "lastLat" to l.latitude,
                "lastLon" to l.longitude,
                "accuracy" to l.accuracy.toDouble(),
                "lastSeen" to now,
                "provider" to (l.provider ?: "fused"),
                "locationEnabled" to isLocationEnabled(),
                "fineLocationGranted" to hasFineLocation(),
                "backgroundLocationGranted" to hasBackgroundLocation(),
                "serviceState" to "tracking",
                "status" to "online",
                "lastError" to ""
            )
            if (refreshFor != null) {
                restPayload["refreshCompletedFor"] = refreshFor
                restPayload["refreshCompletedAt"] = now
                restPayload["refreshResult"] = "ok"
            }
            ChildHttpsBridge.patch(restPayload) { ok, _ -> if (ok) publishLocalStatus("location_https_sent") }
        }

        maybeQueueLocationSample(l, now)
        flushPending()
    }

    private fun maybeQueueLocationSample(l: Location, now: Long) {
        val prev = lastSamplePersisted
        val moved = prev == null || prev.distanceTo(l) >= SAMPLE_DISTANCE_M
        val timed = now - lastSamplePersistedAt >= SAMPLE_MAX_INTERVAL_MS
        if (!moved && !timed) return
        lastSamplePersisted = Location(l)
        lastSamplePersistedAt = now
        val o = JSONObject().put("type", "location_sample").put("id", "sample-$now")
            .put("lat", l.latitude).put("lon", l.longitude).put("accuracy", l.accuracy.toDouble()).put("time", now)
        io.execute { local.insert(o.toString()); flushPending() }
    }

    private fun queueEvent(e: Event) {
        val o = JSONObject()
        when (e) {
            is Event.VisitStarted -> { o.put("type", "visit_start"); o.put("id", e.visit.id); o.put("lat", e.visit.center.lat); o.put("lon", e.visit.center.lon); o.put("accuracy", e.visit.center.accuracyM); o.put("time", e.visit.arrivalMs) }
            is Event.VisitEnded -> { o.put("type", "visit_end"); o.put("id", e.visit.id); o.put("time", e.visit.departureMs) }
            is Event.TripStarted -> { o.put("type", "trip_start"); o.put("id", e.trip.id); o.put("time", e.trip.startMs) }
            is Event.TripPointAdded -> { o.put("type", "trip_point"); o.put("id", e.trip.id); o.put("lat", e.sample.point.lat); o.put("lon", e.sample.point.lon); o.put("accuracy", e.sample.point.accuracyM); o.put("time", e.sample.timeMs) }
            is Event.TripEnded -> { o.put("type", "trip_end"); o.put("id", e.trip.id); o.put("time", e.trip.endMs) }
        }
        io.execute { local.insert(o.toString()); flushPending() }
    }

    private fun flushPending() {
        if (FirebaseAuth.getInstance().currentUser == null || !flushing.compareAndSet(false, true)) return
        io.execute { uploadNext() }
    }

    private fun uploadNext() {
        val p = local.batch(1).firstOrNull()
        if (p == null) { flushing.set(false); return }
        val o = JSONObject(p.json)
        val m = mutableMapOf<String, Any?>()
        o.keys().forEach { k -> m[k] = if (o.isNull(k)) null else o.get(k) }
        cloud.collection("devices").document(CHILD_DOC).collection("events")
            .document("${m["type"]}-${m["id"]}-${m["time"]}").set(m)
            .addOnSuccessListener { io.execute { local.delete(p.localId); uploadNext() } }
            .addOnFailureListener { e -> publishLocalStatus("event_write:${e.javaClass.simpleName}"); flushing.set(false) }
    }

    private fun requestImmediate(refreshFor: Long?) {
        if (!hasFineLocation()) {
            if (refreshFor != null) publishRefreshFailure(refreshFor, "Không có quyền vị trí chính xác")
            publishCloudStatus("permission_missing", "Không có quyền vị trí chính xác")
            return
        }
        if (!isLocationEnabled()) {
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
            client.getCurrentLocation(request, null)
                .addOnSuccessListener { l ->
                    if (l != null) handleCandidate(l, refreshFor)
                    else if (refreshFor != null) publishRefreshFailure(refreshFor, "Chưa lấy được GPS mới")
                    else publishCloudStatus("no_fix", "Chưa lấy được GPS mới")
                }
                .addOnFailureListener { e ->
                    if (refreshFor != null) publishRefreshFailure(refreshFor, "GPS lỗi: ${e.javaClass.simpleName}")
                    else publishCloudStatus("location_error", e.javaClass.simpleName)
                }
        } catch (e: SecurityException) {
            if (refreshFor != null) publishRefreshFailure(refreshFor, "Không đủ quyền vị trí")
            publishLocalStatus("current_security:${e.javaClass.simpleName}")
        }
    }

    private fun publishRefreshFailure(requestId: Long, message: String) {
        if (FirebaseAuth.getInstance().currentUser == null) return
        val payload = mapOf<String, Any?>(
            "refreshFailedFor" to requestId,
            "refreshFailedAt" to System.currentTimeMillis(),
            "refreshResult" to "failed",
            "lastError" to message,
            "locationEnabled" to isLocationEnabled()
        )
        cloud.collection("devices").document(CHILD_DOC).set(payload, SetOptions.merge())
            .addOnFailureListener { e -> publishLocalStatus("refresh_fail_write:${e.javaClass.simpleName}") }
        ChildHttpsBridge.patch(payload) { ok, _ -> if (ok) publishLocalStatus("refresh_fail_https") }
    }

    private fun publishHeartbeat() {
        if (FirebaseAuth.getInstance().currentUser == null) return
        val now = System.currentTimeMillis()
        val service = if (hasFineLocation() && isLocationEnabled()) "tracking" else "attention_needed"
        val data = mapOf<String, Any?>(
            "heartbeatAt" to now,
            "heartbeatServer" to FieldValue.serverTimestamp(),
            "locationEnabled" to isLocationEnabled(),
            "fineLocationGranted" to hasFineLocation(),
            "backgroundLocationGranted" to hasBackgroundLocation(),
            "serviceState" to service,
            "status" to "online"
        )
        cloud.collection("devices").document(CHILD_DOC).set(data, SetOptions.merge())
            .addOnFailureListener { e -> publishLocalStatus("heartbeat_write:${e.javaClass.simpleName}") }

        val rest = data.filterKeys { it != "heartbeatServer" }
        ChildHttpsBridge.patch(rest) { ok, _ -> if (ok) publishLocalStatus("heartbeat_https") }
    }

    private fun publishCloudStatus(status: String, error: String?) {
        if (FirebaseAuth.getInstance().currentUser == null) return
        val data = mutableMapOf<String, Any?>(
            "status" to status,
            "statusAt" to System.currentTimeMillis(),
            "locationEnabled" to isLocationEnabled(),
            "fineLocationGranted" to hasFineLocation(),
            "backgroundLocationGranted" to hasBackgroundLocation(),
            "serviceState" to status,
            "lastError" to error
        )
        cloud.collection("devices").document(CHILD_DOC).set(data, SetOptions.merge())
            .addOnFailureListener { e -> publishLocalStatus("status_write:${e.javaClass.simpleName}") }
        val rest = data.toMutableMap().apply { if (error == null) this["lastError"] = "" }
        ChildHttpsBridge.patch(rest)
    }

    private fun hasFineLocation(): Boolean = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    private fun hasBackgroundLocation(): Boolean = Build.VERSION.SDK_INT < 29 || ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
    private fun isLocationEnabled(): Boolean = try { getSystemService(LocationManager::class.java).isLocationEnabled } catch (_: Exception) { false }

    private fun publishLocalStatus(status: String) {
        getSharedPreferences("tracking_diag", MODE_PRIVATE).edit()
            .putString("status", status)
            .putLong("time", System.currentTimeMillis())
            .apply()
    }

    override fun onDestroy() {
        commandListener?.remove()
        handler.removeCallbacks(heartbeat)
        handler.removeCallbacks(fallbackPoll)
        handler.removeCallbacks(healthCheck)
        if (trackingActive) client.removeLocationUpdates(callback)
        if (networkCallbackRegistered) {
            try { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback) } catch (_: Exception) {}
        }
        io.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val CHILD_DOC = "child-01"
        private const val TRACK_INTERVAL_MS = 60_000L
        private const val TRACK_FASTEST_MS = 30_000L
        private const val TRACK_MAX_DELAY_MS = 120_000L
        private const val TRACK_DISTANCE_M = 20f
        private const val SAMPLE_DISTANCE_M = 50f
        private const val SAMPLE_MAX_INTERVAL_MS = 10 * 60_000L
        private const val HEARTBEAT_MS = 5 * 60_000L
        private const val FALLBACK_POLL_MS = 15_000L
        private const val HEALTH_CHECK_MS = 30_000L
        private const val MAX_ACCURACY_M = 150f
        private const val MAX_LOCATION_AGE_MS = 120_000L
    }
}
