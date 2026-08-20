package com.family.child

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
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
    private val handler = Handler(Looper.getMainLooper())

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach(::handle)
        }
    }

    private val heartbeat = object : Runnable {
        override fun run() {
            publishHealth()
            flushPending()
            handler.postDelayed(this, 5 * 60_000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        client = LocationServices.getFusedLocationProviderClient(this)
        state = StateStore(this)
        state.restore(engine)
        startForeground(42, notification())

        FirebaseAuth.getInstance().signInAnonymously()
            .addOnSuccessListener {
                listenCommands()
                publishHealth()
                requestImmediate()
                flushPending()
            }
            .addOnFailureListener { Log.e("FamilyLocation", "Anonymous auth failed", it) }

        requestTracking()
        handler.post(heartbeat)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getBooleanExtra("immediate", false) == true) requestImmediate()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(): Notification {
        val id = "protection"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(id, "Protection", NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
                enableVibration(false)
            }
        )
        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, id)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("Điện thoại của bạn đang được bảo vệ an toàn")
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun requestTracking() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 60_000L)
            .setMinUpdateIntervalMillis(30_000L)
            .setMinUpdateDistanceMeters(25f)
            .setMaxUpdateDelayMillis(90_000L)
            .build()
        client.requestLocationUpdates(req, callback, mainLooper)
    }

    private fun listenCommands() {
        commandListener?.remove()
        commandListener = cloud.collection("devices").document("child-01")
            .addSnapshotListener { d, error ->
                if (error != null) {
                    Log.e("FamilyLocation", "Command listener failed", error)
                    return@addSnapshotListener
                }
                val ts = d?.getLong("refreshRequestedAt") ?: 0L
                if (ts > lastRefreshSeen) {
                    lastRefreshSeen = ts
                    requestImmediate()
                }
            }
    }

    private fun isUsable(location: Location): Boolean {
        if (location.latitude !in -90.0..90.0 || location.longitude !in -180.0..180.0) return false
        if (!location.hasAccuracy() || location.accuracy <= 0f || location.accuracy > 300f) return false
        val ageMs = ((SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000L).coerceAtLeast(0L)
        if (ageMs > 120_000L) return false

        val previous = lastAccepted
        if (previous != null) {
            val jump = previous.distanceTo(location)
            val dt = (location.time - previous.time).coerceAtLeast(1L)
            if (jump > 50_000f && dt < 10 * 60_000L) return false
        }
        return true
    }

    private fun handle(location: Location) {
        if (!isUsable(location)) {
            Log.w("FamilyLocation", "Rejected stale/inaccurate location")
            return
        }
        lastAccepted = Location(location)
        val now = System.currentTimeMillis()
        val sample = Sample(
            GeoPoint(location.latitude, location.longitude, location.accuracy.toDouble()),
            now,
            moving = location.hasSpeed() && location.speed > 0.8f
        )
        val events = if (engine.currentVisit() == null && engine.currentTrip() == null) {
            engine.seedVisit(sample)
        } else {
            engine.accept(sample)
        }
        state.save(engine)
        events.forEach(::queueEvent)
        publishLatest(location, now)
        flushPending()
    }

    private fun publishLatest(location: Location, now: Long) {
        if (FirebaseAuth.getInstance().currentUser == null) return
        cloud.collection("devices").document("child-01")
            .set(
                mapOf(
                    "lastLat" to location.latitude,
                    "lastLon" to location.longitude,
                    "accuracy" to location.accuracy.toDouble(),
                    "lastSeen" to now,
                    "heartbeatAt" to now,
                    "locationEnabled" to isSystemLocationEnabled(),
                    "backgroundLocationGranted" to hasBackgroundLocation(),
                    "serviceRunning" to true
                ),
                SetOptions.merge()
            )
            .addOnFailureListener { Log.e("FamilyLocation", "Latest location upload failed", it) }
    }

    private fun publishHealth() {
        if (FirebaseAuth.getInstance().currentUser == null) return
        val now = System.currentTimeMillis()
        cloud.collection("devices").document("child-01")
            .set(
                mapOf(
                    "heartbeatAt" to now,
                    "locationEnabled" to isSystemLocationEnabled(),
                    "backgroundLocationGranted" to hasBackgroundLocation(),
                    "serviceRunning" to true
                ),
                SetOptions.merge()
            )
            .addOnFailureListener { Log.e("FamilyLocation", "Heartbeat upload failed", it) }
    }

    private fun isSystemLocationEnabled(): Boolean =
        getSystemService(LocationManager::class.java)?.isLocationEnabled == true

    private fun hasBackgroundLocation(): Boolean =
        android.os.Build.VERSION.SDK_INT < 29 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun queueEvent(event: Event) {
        val o = JSONObject()
        when (event) {
            is Event.VisitStarted -> {
                o.put("type", "visit_start")
                o.put("id", event.visit.id)
                o.put("lat", event.visit.center.lat)
                o.put("lon", event.visit.center.lon)
                o.put("time", event.visit.arrivalMs)
            }
            is Event.VisitEnded -> {
                o.put("type", "visit_end")
                o.put("id", event.visit.id)
                o.put("lat", event.visit.center.lat)
                o.put("lon", event.visit.center.lon)
                o.put("time", event.visit.departureMs ?: System.currentTimeMillis())
            }
            is Event.TripStarted -> {
                val first = event.trip.points.firstOrNull()
                o.put("type", "trip_start")
                o.put("id", event.trip.id)
                first?.let {
                    o.put("lat", it.point.lat)
                    o.put("lon", it.point.lon)
                }
                o.put("time", event.trip.startMs)
            }
            is Event.TripPointAdded -> {
                o.put("type", "trip_point")
                o.put("id", event.trip.id)
                o.put("lat", event.sample.point.lat)
                o.put("lon", event.sample.point.lon)
                o.put("time", event.sample.timeMs)
            }
            is Event.TripEnded -> {
                val last = event.trip.points.lastOrNull()
                o.put("type", "trip_end")
                o.put("id", event.trip.id)
                last?.let {
                    o.put("lat", it.point.lat)
                    o.put("lon", it.point.lon)
                }
                o.put("time", event.trip.endMs ?: System.currentTimeMillis())
            }
        }
        io.execute { local.insert(o.toString()) }
    }

    private fun flushPending() {
        if (FirebaseAuth.getInstance().currentUser == null || !flushing.compareAndSet(false, true)) return
        io.execute { uploadNext() }
    }

    private fun uploadNext() {
        val pending = local.batch(1).firstOrNull()
        if (pending == null) {
            flushing.set(false)
            return
        }
        val o = JSONObject(pending.json)
        val data = mutableMapOf<String, Any?>()
        o.keys().forEach { key -> data[key] = o.get(key) }
        cloud.collection("devices").document("child-01").collection("events").add(data)
            .addOnSuccessListener {
                io.execute {
                    local.delete(pending.localId)
                    uploadNext()
                }
            }
            .addOnFailureListener {
                Log.e("FamilyLocation", "Event upload failed", it)
                flushing.set(false)
            }
    }

    fun requestImmediate() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { location -> if (location != null) handle(location) }
            .addOnFailureListener { Log.e("FamilyLocation", "Immediate location failed", it) }
    }

    override fun onDestroy() {
        commandListener?.remove()
        handler.removeCallbacksAndMessages(null)
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            client.removeLocationUpdates(callback)
        }
        if (FirebaseAuth.getInstance().currentUser != null) {
            cloud.collection("devices").document("child-01")
                .set(mapOf("serviceRunning" to false, "heartbeatAt" to System.currentTimeMillis()), SetOptions.merge())
        }
        io.shutdown()
        super.onDestroy()
    }
}
