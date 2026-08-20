package com.family.child

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.family.core.*
import com.google.android.gms.location.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
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

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) { result.locations.forEach(::handleCandidate) }
    }

    override fun onCreate() {
        super.onCreate()
        client = LocationServices.getFusedLocationProviderClient(this)
        state = StateStore(this)
        state.restore(engine)
        startForeground(42, notification())
        publishLocalStatus("starting")
        FirebaseAuth.getInstance().signInAnonymously()
            .addOnSuccessListener {
                listenCommands()
                flushPending()
                publishCloudStatus("online", null)
                requestImmediate()
            }
            .addOnFailureListener { e -> publishLocalStatus("auth_error:${e.javaClass.simpleName}") }
        requestTracking()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getBooleanExtra("immediate", false) == true) requestImmediate()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(): Notification {
        val id = "protection"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(id, "Protection", NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null); enableVibration(false) })
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, id)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("Điện thoại của bạn đang được bảo vệ an toàn")
            .setContentIntent(pi).setOngoing(true).setSilent(true).build()
    }

    private fun requestTracking() {
        if (!hasFineLocation()) { publishLocalStatus("location_permission_missing"); return }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 120_000L)
            .setMinUpdateIntervalMillis(45_000L)
            .setMinUpdateDistanceMeters(40f)
            .setMaxUpdateDelayMillis(180_000L)
            .setWaitForAccurateLocation(false)
            .build()
        client.requestLocationUpdates(request, callback, mainLooper)
    }

    private fun listenCommands() {
        commandListener?.remove()
        commandListener = cloud.collection("devices").document(CHILD_DOC).addSnapshotListener { d, error ->
            if (error != null) { publishLocalStatus("command_listener:${error.code}"); return@addSnapshotListener }
            val ts = d?.getLong("refreshRequestedAt") ?: 0L
            if (ts > lastRefreshSeen) { lastRefreshSeen = ts; requestImmediate() }
        }
    }

    private fun handleCandidate(location: Location) {
        if (!isUsable(location)) return
        val previous = lastAccepted
        if (previous != null) {
            val jumpM = previous.distanceTo(location)
            val elapsedSec = ((location.elapsedRealtimeNanos - previous.elapsedRealtimeNanos) / 1_000_000_000.0).coerceAtLeast(1.0)
            if (jumpM > 20_000 && elapsedSec < 300 && location.accuracy >= previous.accuracy * 0.8f) return
        }
        lastAccepted = location
        handle(location)
    }

    private fun isUsable(location: Location): Boolean {
        if (!location.hasAccuracy() || location.accuracy > 250f) return false
        val ageMs = ((SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000L).coerceAtLeast(0L)
        return ageMs <= 180_000L
    }

    private fun handle(l: Location) {
        val now = System.currentTimeMillis()
        val sample = Sample(GeoPoint(l.latitude, l.longitude, l.accuracy.toDouble()), now, moving = l.hasSpeed() && l.speed > 0.8f)
        val events = if (engine.currentVisit() == null && engine.currentTrip() == null) engine.seedVisit(sample) else engine.accept(sample)
        state.save(engine)
        events.forEach(::queueEvent)

        if (FirebaseAuth.getInstance().currentUser != null) {
            val payload = mapOf(
                "lastLat" to l.latitude,
                "lastLon" to l.longitude,
                "accuracy" to l.accuracy.toDouble(),
                "lastSeen" to now,
                "provider" to (l.provider ?: "fused"),
                "locationEnabled" to isLocationEnabled(),
                "status" to "online"
            )
            cloud.collection("devices").document(CHILD_DOC).set(payload, SetOptions.merge())
                .addOnFailureListener { e -> publishLocalStatus("device_write:${e.javaClass.simpleName}") }
        }

        maybeQueueLocationSample(l, now)
        flushPending()
    }

    private fun maybeQueueLocationSample(l: Location, now: Long) {
        val prev = lastSamplePersisted
        val moved = prev == null || prev.distanceTo(l) >= 80f
        val timed = now - lastSamplePersistedAt >= 30 * 60_000L
        if (!moved && !timed) return
        lastSamplePersisted = Location(l)
        lastSamplePersistedAt = now
        val o = JSONObject().put("type", "location_sample").put("id", "sample-$now")
            .put("lat", l.latitude).put("lon", l.longitude).put("accuracy", l.accuracy.toDouble()).put("time", now)
        io.execute { local.insert(o.toString()) }
    }

    private fun queueEvent(e: Event) {
        val o = JSONObject()
        when (e) {
            is Event.VisitStarted -> { o.put("type", "visit_start"); o.put("id", e.visit.id); o.put("lat", e.visit.center.lat); o.put("lon", e.visit.center.lon); o.put("time", e.visit.arrivalMs) }
            is Event.VisitEnded -> { o.put("type", "visit_end"); o.put("id", e.visit.id); o.put("time", e.visit.departureMs) }
            is Event.TripStarted -> { o.put("type", "trip_start"); o.put("id", e.trip.id); o.put("time", e.trip.startMs) }
            is Event.TripPointAdded -> { o.put("type", "trip_point"); o.put("id", e.trip.id); o.put("lat", e.sample.point.lat); o.put("lon", e.sample.point.lon); o.put("time", e.sample.timeMs) }
            is Event.TripEnded -> { o.put("type", "trip_end"); o.put("id", e.trip.id); o.put("time", e.trip.endMs) }
        }
        io.execute { local.insert(o.toString()) }
    }

    private fun flushPending() {
        if (FirebaseAuth.getInstance().currentUser == null || !flushing.compareAndSet(false, true)) return
        io.execute { uploadNext() }
    }

    private fun uploadNext() {
        val p = local.batch(1).firstOrNull()
        if (p == null) { flushing.set(false); return }
        val o = JSONObject(p.json); val m = mutableMapOf<String, Any?>(); o.keys().forEach { k -> m[k] = o.get(k) }
        cloud.collection("devices").document(CHILD_DOC).collection("events").add(m)
            .addOnSuccessListener { io.execute { local.delete(p.localId); uploadNext() } }
            .addOnFailureListener { e -> publishLocalStatus("event_write:${e.javaClass.simpleName}"); flushing.set(false) }
    }

    private fun requestImmediate() {
        if (!hasFineLocation()) { publishCloudStatus("permission_missing", "Không có quyền vị trí"); return }
        if (!isLocationEnabled()) { publishCloudStatus("location_off", "Vị trí trên thiết bị đang tắt"); return }
        cloud.collection("devices").document(CHILD_DOC).set(mapOf("lastAttempt" to System.currentTimeMillis(), "locationEnabled" to true), SetOptions.merge())
        val request = CurrentLocationRequest.Builder().setPriority(Priority.PRIORITY_HIGH_ACCURACY).setMaxUpdateAgeMillis(15_000L).setDurationMillis(20_000L).build()
        client.getCurrentLocation(request, null)
            .addOnSuccessListener { l -> if (l != null) handleCandidate(l) else publishCloudStatus("no_fix", "Chưa lấy được vị trí mới") }
            .addOnFailureListener { e -> publishCloudStatus("location_error", e.javaClass.simpleName) }
    }

    private fun hasFineLocation(): Boolean = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    private fun isLocationEnabled(): Boolean = try { getSystemService(LocationManager::class.java).isLocationEnabled } catch (_: Exception) { false }

    private fun publishCloudStatus(status: String, error: String?) {
        if (FirebaseAuth.getInstance().currentUser == null) return
        val data = mutableMapOf<String, Any?>("status" to status, "statusAt" to System.currentTimeMillis(), "locationEnabled" to isLocationEnabled())
        data["lastError"] = error
        cloud.collection("devices").document(CHILD_DOC).set(data, SetOptions.merge())
            .addOnFailureListener { e -> publishLocalStatus("status_write:${e.javaClass.simpleName}") }
    }

    private fun publishLocalStatus(status: String) {
        getSharedPreferences("tracking_diag", MODE_PRIVATE).edit().putString("status", status).putLong("time", System.currentTimeMillis()).apply()
    }

    override fun onDestroy() { commandListener?.remove(); client.removeLocationUpdates(callback); io.shutdown(); super.onDestroy() }
    companion object { private const val CHILD_DOC = "child-01" }
}
