package com.family.parent

import android.Manifest
import android.app.DatePickerDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.annotations.PolygonOptions
import org.maplibre.android.annotations.PolylineOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.*

private const val CHILD_DOC = "child-01"
private const val OSM_STYLE = "asset://osm_raster_style.json"
private const val CONNECTION_WARN_MS = 10 * 60_000L
private const val CONNECTION_LOST_MS = 15 * 60_000L
private const val DIAGNOSTIC_FRESH_MS = 10 * 60_000L
private const val REMINDER_TTL_MS = 10 * 60_000L
private const val REMINDER_COOLDOWN_MS = 45_000L

data class UiEvent(
    val type: String = "",
    val time: Long = 0L,
    val lat: Double? = null,
    val lon: Double? = null,
    val accuracy: Double? = null,
    val id: String = ""
)

data class UiVisit(
    val index: Int,
    val arrival: Long,
    val departure: Long?,
    val durationMs: Long,
    val lat: Double?,
    val lon: Double?,
    val accuracy: Double?
)

data class JourneySummary(
    val distanceMeters: Double,
    val validPoints: Int,
    val stops: Int,
    val movingMs: Long,
    val stoppedMs: Long
)

enum class ParentTab { HOME, JOURNEY, HEALTH, SETTINGS }
enum class ConnectionLevel { CONNECTED, WARNING, LOST, UNKNOWN }

data class ConnectionAssessment(
    val level: ConnectionLevel,
    val title: String,
    val detail: String,
    val lastContactAt: Long
)

class MainActivity : ComponentActivity() {
    private var mapView: MapView? = null
    private var restoredState: Bundle? = null
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restoredState = savedInstanceState
        MapLibre.getInstance(this)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent { ParentApp(restoredState) { mapView = it } }
    }

    override fun onStart() { super.onStart(); mapView?.onStart() }
    override fun onResume() { super.onResume(); FirebaseFirestore.getInstance().enableNetwork(); mapView?.onResume() }
    override fun onPause() { mapView?.onPause(); super.onPause() }
    override fun onStop() { mapView?.onStop(); super.onStop() }
    override fun onLowMemory() { super.onLowMemory(); mapView?.onLowMemory() }
    override fun onSaveInstanceState(outState: Bundle) { mapView?.onSaveInstanceState(outState); super.onSaveInstanceState(outState) }
    override fun onDestroy() { mapView?.onDestroy(); mapView = null; super.onDestroy() }
}

@Composable
fun ParentApp(savedState: Bundle?, onMapViewCreated: (MapView) -> Unit) {
    var authReady by remember { mutableStateOf(FirebaseAuth.getInstance().currentUser != null) }
    var authError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        if (authReady) {
            FirebaseFirestore.getInstance().enableNetwork()
        } else {
            FirebaseAuth.getInstance().signInAnonymously()
                .addOnSuccessListener { authReady = true; FirebaseFirestore.getInstance().enableNetwork() }
                .addOnFailureListener { authError = it.message ?: it.javaClass.simpleName }
        }
    }

    val scheme = darkColorScheme(
        primary = Color(0xFF55D8FF),
        background = Color(0xFF06090E),
        surface = Color(0xFF101720),
        surfaceVariant = Color(0xFF151F2A),
        error = Color(0xFFFF7D7D)
    )
    MaterialTheme(colorScheme = scheme) {
        when {
            authError != null -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("Không đăng nhập được Firebase: $authError", color = MaterialTheme.colorScheme.error)
            }
            !authReady -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else -> ParentDashboard(savedState, onMapViewCreated)
        }
    }
}

@Composable
fun ParentDashboard(savedState: Bundle?, onMapViewCreated: (MapView) -> Unit) {
    val context = LocalContext.current
    val db = remember { FirebaseFirestore.getInstance() }
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableStateOf(ParentTab.HOME) }
    var tick by remember { mutableLongStateOf(System.currentTimeMillis()) }

    var lat by remember { mutableStateOf<Double?>(null) }
    var lon by remember { mutableStateOf<Double?>(null) }
    var seen by remember { mutableLongStateOf(0L) }
    var acc by remember { mutableDoubleStateOf(0.0) }
    var heartbeatAt by remember { mutableLongStateOf(0L) }
    var diagnosticAt by remember { mutableLongStateOf(0L) }
    var locationEnabled by remember { mutableStateOf<Boolean?>(null) }
    var fineLocationGranted by remember { mutableStateOf<Boolean?>(null) }
    var backgroundLocationGranted by remember { mutableStateOf<Boolean?>(null) }
    var notificationGranted by remember { mutableStateOf<Boolean?>(null) }
    var serviceState by remember { mutableStateOf("unknown") }
    var deviceStatus by remember { mutableStateOf("unknown") }
    var lastError by remember { mutableStateOf<String?>(null) }
    var networkType by remember { mutableStateOf<String?>(null) }
    var networkValidated by remember { mutableStateOf<Boolean?>(null) }
    var networkAt by remember { mutableLongStateOf(0L) }
    var firebaseRealtimeOkAt by remember { mutableLongStateOf(0L) }
    var firebaseWriteOkAt by remember { mutableLongStateOf(0L) }
    var firebaseLatencyMs by remember { mutableLongStateOf(0L) }
    var httpsFallbackOkAt by remember { mutableLongStateOf(0L) }
    var httpsLatencyMs by remember { mutableLongStateOf(0L) }
    var serviceStartedAt by remember { mutableLongStateOf(0L) }
    var childAppVersion by remember { mutableStateOf<String?>(null) }

    var events by remember { mutableStateOf(listOf<UiEvent>()) }
    var firebaseError by remember { mutableStateOf<String?>(null) }
    var deviceFromCache by remember { mutableStateOf(false) }
    var eventFromCache by remember { mutableStateOf(false) }
    var lastServerSnapshotAt by remember { mutableLongStateOf(0L) }
    var lastHttpsSuccessAt by remember { mutableLongStateOf(0L) }
    var httpsFallbackActive by remember { mutableStateOf(false) }

    var refreshText by remember { mutableStateOf("Sẵn sàng cập nhật vị trí") }
    var refreshRequestId by remember { mutableLongStateOf(0L) }
    var commandConfirmedFor by remember { mutableLongStateOf(0L) }

    var reminderText by remember { mutableStateOf("") }
    var reminderRequestId by remember { mutableLongStateOf(0L) }
    var reminderConfirmedFor by remember { mutableLongStateOf(0L) }
    var lastReminderSentAt by remember { mutableLongStateOf(0L) }
    var locationReminderAckFor by remember { mutableLongStateOf(0L) }
    var locationReminderResult by remember { mutableStateOf<String?>(null) }

    var selectedDayStart by remember { mutableLongStateOf(startOfToday()) }

    fun processRefreshState(ack: Long, completed: Long, failed: Long, error: String?) {
        val request = refreshRequestId
        if (request <= 0L) return
        when {
            completed == request -> {
                refreshText = "Đã nhận vị trí mới lúc ${if (seen > 0) fmt(seen) else fmt(System.currentTimeMillis())}"
                refreshRequestId = 0L
            }
            failed == request -> {
                refreshText = "Máy Con không cập nhật được: ${error ?: "không lấy được GPS"}"
                refreshRequestId = 0L
            }
            ack == request -> refreshText = "Máy Con đã nhận yêu cầu · đang lấy GPS..."
        }
    }

    fun processReminderState(ackFor: Long, result: String?) {
        locationReminderAckFor = ackFor
        locationReminderResult = result
        val request = reminderRequestId
        if (request <= 0L || ackFor != request) return
        reminderText = when (result) {
            "shown" -> "Máy Con đã nhận và hiện lời nhắc bật Vị trí."
            "already_on" -> "Máy Con đã bật Vị trí trước khi nhận lời nhắc."
            "notification_permission_missing" -> "Máy Con chưa cấp quyền thông báo nên chưa thể hiện lời nhắc."
            "expired" -> "Lời nhắc đã hết hạn trước khi Máy Con nhận."
            else -> "Máy Con đã nhận lời nhắc."
        }
        reminderRequestId = 0L
    }

    fun applyRestState(s: ParentHttpsBridge.DeviceState) {
        if (s.lastLat != null) lat = s.lastLat
        if (s.lastLon != null) lon = s.lastLon
        if (s.accuracy != null) acc = s.accuracy
        if (s.lastSeen > 0L) seen = s.lastSeen
        if (s.heartbeatAt > 0L) heartbeatAt = s.heartbeatAt
        if (s.diagnosticAt > 0L) diagnosticAt = s.diagnosticAt
        if (s.locationEnabled != null) locationEnabled = s.locationEnabled
        if (s.fineLocationGranted != null) fineLocationGranted = s.fineLocationGranted
        if (s.backgroundLocationGranted != null) backgroundLocationGranted = s.backgroundLocationGranted
        if (s.notificationGranted != null) notificationGranted = s.notificationGranted
        serviceState = s.serviceState ?: serviceState
        deviceStatus = s.status ?: deviceStatus
        lastError = s.lastError
        networkType = s.networkType ?: networkType
        if (s.networkValidated != null) networkValidated = s.networkValidated
        if (s.networkAt > 0L) networkAt = s.networkAt
        if (s.firebaseRealtimeOkAt > 0L) firebaseRealtimeOkAt = s.firebaseRealtimeOkAt
        if (s.firebaseWriteOkAt > 0L) firebaseWriteOkAt = s.firebaseWriteOkAt
        if (s.firebaseLatencyMs > 0L) firebaseLatencyMs = s.firebaseLatencyMs
        if (s.httpsFallbackOkAt > 0L) httpsFallbackOkAt = s.httpsFallbackOkAt
        if (s.httpsLatencyMs > 0L) httpsLatencyMs = s.httpsLatencyMs
        if (s.serviceStartedAt > 0L) serviceStartedAt = s.serviceStartedAt
        childAppVersion = s.appVersion ?: childAppVersion
        processRefreshState(s.refreshAckFor, s.refreshCompletedFor, s.refreshFailedFor, s.lastError)
        processReminderState(s.locationReminderAckFor, s.locationReminderResult)
    }

    DisposableEffect(Unit) {
        val deviceListener = db.collection("devices").document(CHILD_DOC)
            .addSnapshotListener(MetadataChanges.INCLUDE) { d, error ->
                if (error != null) {
                    firebaseError = "Thiết bị: ${error.code}"
                    return@addSnapshotListener
                }
                if (d == null) return@addSnapshotListener
                deviceFromCache = d.metadata.isFromCache
                if (!d.metadata.isFromCache) {
                    lastServerSnapshotAt = System.currentTimeMillis()
                    httpsFallbackActive = false
                    firebaseError = null
                }
                if (!d.exists()) return@addSnapshotListener
                lat = d.getDouble("lastLat")
                lon = d.getDouble("lastLon")
                seen = d.getLong("lastSeen") ?: 0L
                acc = d.getDouble("accuracy") ?: 0.0
                heartbeatAt = d.getLong("heartbeatAt") ?: 0L
                diagnosticAt = d.getLong("diagnosticAt") ?: diagnosticAt
                locationEnabled = d.getBoolean("locationEnabled")
                fineLocationGranted = d.getBoolean("fineLocationGranted")
                backgroundLocationGranted = d.getBoolean("backgroundLocationGranted")
                notificationGranted = d.getBoolean("notificationGranted")
                serviceState = d.getString("serviceState") ?: d.getString("status") ?: "unknown"
                deviceStatus = d.getString("status") ?: deviceStatus
                lastError = d.getString("lastError")?.takeIf { it.isNotBlank() }
                networkType = d.getString("networkType") ?: networkType
                networkValidated = d.getBoolean("networkValidated")
                networkAt = d.getLong("networkAt") ?: networkAt
                firebaseRealtimeOkAt = d.getLong("firebaseRealtimeOkAt") ?: firebaseRealtimeOkAt
                firebaseWriteOkAt = d.getLong("firebaseWriteOkAt") ?: firebaseWriteOkAt
                firebaseLatencyMs = d.getLong("firebaseLatencyMs") ?: firebaseLatencyMs
                httpsFallbackOkAt = d.getLong("httpsFallbackOkAt") ?: httpsFallbackOkAt
                httpsLatencyMs = d.getLong("httpsLatencyMs") ?: httpsLatencyMs
                serviceStartedAt = d.getLong("serviceStartedAt") ?: serviceStartedAt
                childAppVersion = d.getString("appVersion") ?: childAppVersion
                processRefreshState(
                    d.getLong("refreshAckFor") ?: 0L,
                    d.getLong("refreshCompletedFor") ?: 0L,
                    d.getLong("refreshFailedFor") ?: 0L,
                    lastError
                )
                processReminderState(
                    d.getLong("locationReminderAckFor") ?: 0L,
                    d.getString("locationReminderResult")
                )
            }

        val eventListener = db.collection("devices").document(CHILD_DOC).collection("events")
            .orderBy("time").limitToLast(5000)
            .addSnapshotListener(MetadataChanges.INCLUDE) { q, error ->
                if (error != null) firebaseError = "Nhật ký: ${error.code}"
                else if (q != null) {
                    eventFromCache = q.metadata.isFromCache
                    events = q.documents.mapNotNull { it.toObject(UiEvent::class.java) }
                }
            }

        val cm = context.getSystemService(ConnectivityManager::class.java)
        val main = Handler(Looper.getMainLooper())
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                db.enableNetwork()
                main.post {
                    if (deviceFromCache && refreshRequestId > 0L) refreshText = "Mạng đã trở lại · đang nối lại máy chủ..."
                }
            }
        }
        var registered = false
        try { cm.registerDefaultNetworkCallback(callback); registered = true } catch (_: Exception) {}

        onDispose {
            deviceListener.remove()
            eventListener.remove()
            if (registered) try { cm.unregisterNetworkCallback(callback) } catch (_: Exception) {}
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(10_000L)
            tick = System.currentTimeMillis()
            val serverStale = lastServerSnapshotAt == 0L || tick - lastServerSnapshotAt > 25_000L
            if (deviceFromCache || serverStale || refreshRequestId > 0L || reminderRequestId > 0L) {
                ParentHttpsBridge.readDevice { state, _ ->
                    if (state != null) {
                        lastHttpsSuccessAt = System.currentTimeMillis()
                        if (deviceFromCache || serverStale) httpsFallbackActive = true
                        applyRestState(state)
                    }
                }
            }
        }
    }

    LaunchedEffect(refreshRequestId) {
        val request = refreshRequestId
        if (request > 0L) {
            repeat(15) { index ->
                delay(3_000L)
                if (refreshRequestId != request) return@LaunchedEffect
                ParentHttpsBridge.readDevice { state, _ ->
                    if (state != null) {
                        lastHttpsSuccessAt = System.currentTimeMillis()
                        applyRestState(state)
                    }
                }
                if (index == 2 && commandConfirmedFor != request && refreshRequestId == request) {
                    refreshText = "Chưa xác nhận được lệnh qua Firebase · đang kiểm tra HTTPS dự phòng..."
                }
            }
            if (refreshRequestId == request) {
                refreshText = "Máy Con chưa phản hồi sau 45 giây"
                refreshRequestId = 0L
            }
        }
    }

    LaunchedEffect(reminderRequestId) {
        val request = reminderRequestId
        if (request > 0L) {
            repeat(15) {
                delay(3_000L)
                if (reminderRequestId != request) return@LaunchedEffect
                ParentHttpsBridge.readDevice { state, _ -> if (state != null) applyRestState(state) }
            }
            if (reminderRequestId == request) {
                reminderText = "Chưa xác nhận được Máy Con đã nhận lời nhắc."
                reminderRequestId = 0L
            }
        }
    }

    val now = tick
    val dayEnd = selectedDayStart + 24L * 60 * 60 * 1000
    val dayEvents = remember(events, selectedDayStart) { events.filter { it.time in selectedDayStart until dayEnd } }
    val visits = remember(dayEvents, selectedDayStart, tick) { toVisits(dayEvents, selectedDayStart, dayEnd) }
    val journeySummary = remember(dayEvents, visits, tick) { calculateJourneySummary(dayEvents, visits, selectedDayStart, dayEnd) }
    val isToday = selectedDayStart == startOfToday()
    val assessment = assessConnection(
        now = now,
        seen = seen,
        heartbeatAt = heartbeatAt,
        diagnosticAt = diagnosticAt,
        networkValidated = networkValidated,
        networkAt = networkAt,
        firebaseRealtimeOkAt = firebaseRealtimeOkAt,
        firebaseWriteOkAt = firebaseWriteOkAt,
        httpsFallbackOkAt = httpsFallbackOkAt,
        serviceState = serviceState,
        locationEnabled = locationEnabled
    )
    val locationStateFresh = diagnosticAt > 0L && now - diagnosticAt <= DIAGNOSTIC_FRESH_MS
    val confirmedLocationOff = locationStateFresh && locationEnabled == false
    val firebaseChildRecent = maxOf(firebaseRealtimeOkAt, firebaseWriteOkAt) > 0L && now - maxOf(firebaseRealtimeOkAt, firebaseWriteOkAt) <= DIAGNOSTIC_FRESH_MS
    val httpsChildRecent = httpsFallbackOkAt > 0L && now - httpsFallbackOkAt <= DIAGNOSTIC_FRESH_MS
    val parentHttpsRecent = lastHttpsSuccessAt > 0L && now - lastHttpsSuccessAt <= 45_000L

    LaunchedEffect(assessment.level, assessment.lastContactAt) {
        val prefs = context.getSharedPreferences("parent_alerts", Context.MODE_PRIVATE)
        if (assessment.level == ConnectionLevel.WARNING || assessment.level == ConnectionLevel.LOST) {
            val episode = assessment.lastContactAt
            if (episode > 0L && prefs.getLong("connection_episode", -1L) != episode) {
                notifyConnectionIssue(context, assessment.title, assessment.detail)
                prefs.edit().putLong("connection_episode", episode).apply()
            }
        } else if (assessment.level == ConnectionLevel.CONNECTED) {
            prefs.edit().remove("connection_episode").apply()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF0B1118)) {
                ParentTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        icon = { Text(tabIcon(tab), style = MaterialTheme.typography.titleMedium) },
                        label = { Text(tabLabel(tab), style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }
        }
    ) { pad ->
        when (selectedTab) {
            ParentTab.HOME -> HomeScreen(
                modifier = Modifier.padding(pad),
                assessment = assessment,
                lat = lat,
                lon = lon,
                seen = seen,
                accuracy = acc,
                dayEvents = dayEvents,
                savedState = savedState,
                onMapViewCreated = onMapViewCreated,
                refreshText = refreshText,
                refreshBusy = refreshRequestId > 0L,
                onRefresh = {
                    if (refreshRequestId > 0L) return@HomeScreen
                    firebaseError = null
                    val requestAt = System.currentTimeMillis()
                    refreshRequestId = requestAt
                    commandConfirmedFor = 0L
                    refreshText = "Đang gửi yêu cầu cập nhật..."
                    val payload = mapOf<String, Any?>("refreshRequestedAt" to requestAt)
                    db.collection("devices").document(CHILD_DOC).set(payload, SetOptions.merge())
                        .addOnSuccessListener {
                            if (refreshRequestId == requestAt) {
                                commandConfirmedFor = requestAt
                                refreshText = "Đã gửi yêu cầu · chờ Máy Con phản hồi..."
                            }
                        }
                        .addOnFailureListener { e ->
                            if (refreshRequestId == requestAt) firebaseError = "Firebase: ${e.message ?: e.javaClass.simpleName}"
                        }
                    scope.launch {
                        delay(3_500L)
                        if (refreshRequestId == requestAt && commandConfirmedFor != requestAt) {
                            refreshText = "Firebase phản hồi chậm · đang thử HTTPS dự phòng..."
                            ParentHttpsBridge.patch(payload) { ok, error ->
                                if (refreshRequestId == requestAt) {
                                    if (ok) {
                                        commandConfirmedFor = requestAt
                                        lastHttpsSuccessAt = System.currentTimeMillis()
                                        httpsFallbackActive = true
                                        refreshText = "Đã gửi qua HTTPS dự phòng · chờ Máy Con phản hồi..."
                                    } else if (commandConfirmedFor != requestAt) {
                                        refreshText = "Chưa gửi được lệnh: ${error ?: "mạng hiện tại không tới được máy chủ"}"
                                    }
                                }
                            }
                        }
                    }
                },
                confirmedLocationOff = confirmedLocationOff,
                locationDiagnosticAt = diagnosticAt,
                reminderText = reminderText,
                reminderBusy = reminderRequestId > 0L,
                reminderCooldown = (REMINDER_COOLDOWN_MS - (now - lastReminderSentAt)).coerceAtLeast(0L),
                onReminder = {
                    if (!confirmedLocationOff || reminderRequestId > 0L || now - lastReminderSentAt < REMINDER_COOLDOWN_MS) return@HomeScreen
                    val requestAt = System.currentTimeMillis()
                    val expiresAt = requestAt + REMINDER_TTL_MS
                    lastReminderSentAt = requestAt
                    reminderRequestId = requestAt
                    reminderConfirmedFor = 0L
                    reminderText = "Đang gửi lời nhắc bật Vị trí..."
                    val payload = mapOf<String, Any?>(
                        "locationReminderRequestedAt" to requestAt,
                        "locationReminderExpiresAt" to expiresAt
                    )
                    db.collection("devices").document(CHILD_DOC).set(payload, SetOptions.merge())
                        .addOnSuccessListener {
                            if (reminderRequestId == requestAt) {
                                reminderConfirmedFor = requestAt
                                reminderText = "Đã gửi lời nhắc · chờ Máy Con xác nhận..."
                            }
                        }
                        .addOnFailureListener { e ->
                            if (reminderRequestId == requestAt) firebaseError = "Nhắc bật Vị trí: ${e.message ?: e.javaClass.simpleName}"
                        }
                    scope.launch {
                        delay(3_500L)
                        if (reminderRequestId == requestAt && reminderConfirmedFor != requestAt) {
                            reminderText = "Firebase phản hồi chậm · đang thử HTTPS dự phòng..."
                            ParentHttpsBridge.patch(payload) { ok, error ->
                                if (reminderRequestId == requestAt) {
                                    if (ok) {
                                        reminderConfirmedFor = requestAt
                                        reminderText = "Đã gửi lời nhắc qua HTTPS dự phòng."
                                    } else {
                                        reminderText = "Chưa gửi được lời nhắc: ${error ?: "lỗi mạng"}"
                                    }
                                }
                            }
                        }
                    }
                },
                journeySummary = journeySummary,
                onOpenJourney = { selectedTab = ParentTab.JOURNEY },
                onOpenHealth = { selectedTab = ParentTab.HEALTH },
                onGoogleMaps = { if (lat != null && lon != null) openGoogleMapsPoint(context, lat!!, lon!!) },
                onDirections = { if (lat != null && lon != null) openGoogleMapsDirections(context, lat!!, lon!!) },
                firebaseError = firebaseError,
                usingFallback = httpsFallbackActive && parentHttpsRecent,
                deviceFromCache = deviceFromCache || eventFromCache,
                locationEnabled = locationEnabled,
                fineLocationGranted = fineLocationGranted
            )

            ParentTab.JOURNEY -> JourneyScreen(
                modifier = Modifier.padding(pad),
                selectedDayStart = selectedDayStart,
                onSelectDay = { selectedDayStart = it },
                isToday = isToday,
                dayEvents = dayEvents,
                visits = visits,
                summary = journeySummary,
                lat = lat,
                lon = lon,
                accuracy = acc,
                savedState = savedState,
                onMapViewCreated = onMapViewCreated,
                onOpenPoint = { pLat, pLon -> openGoogleMapsPoint(context, pLat, pLon) }
            )

            ParentTab.HEALTH -> HealthScreen(
                modifier = Modifier.padding(pad),
                assessment = assessment,
                now = now,
                networkType = networkType,
                networkValidated = networkValidated,
                networkAt = networkAt,
                firebaseRecent = firebaseChildRecent,
                firebaseAt = maxOf(firebaseRealtimeOkAt, firebaseWriteOkAt),
                firebaseLatencyMs = firebaseLatencyMs,
                httpsRecent = httpsChildRecent,
                httpsAt = httpsFallbackOkAt,
                httpsLatencyMs = httpsLatencyMs,
                locationEnabled = locationEnabled,
                fineLocationGranted = fineLocationGranted,
                backgroundLocationGranted = backgroundLocationGranted,
                notificationGranted = notificationGranted,
                diagnosticAt = diagnosticAt,
                serviceState = serviceState,
                serviceStartedAt = serviceStartedAt,
                childAppVersion = childAppVersion,
                lastError = lastError,
                parentRealtimeOk = !deviceFromCache && lastServerSnapshotAt > 0L && now - lastServerSnapshotAt <= 45_000L,
                parentHttpsOk = parentHttpsRecent,
                parentHttpsLatencyMs = ParentHttpsBridge.lastLatencyMs
            )

            ParentTab.SETTINGS -> SettingsScreen(
                modifier = Modifier.padding(pad),
                childAppVersion = childAppVersion,
                onHealth = { selectedTab = ParentTab.HEALTH }
            )
        }
    }
}

@Composable
private fun HomeScreen(
    modifier: Modifier,
    assessment: ConnectionAssessment,
    lat: Double?,
    lon: Double?,
    seen: Long,
    accuracy: Double,
    dayEvents: List<UiEvent>,
    savedState: Bundle?,
    onMapViewCreated: (MapView) -> Unit,
    refreshText: String,
    refreshBusy: Boolean,
    onRefresh: () -> Unit,
    confirmedLocationOff: Boolean,
    locationDiagnosticAt: Long,
    reminderText: String,
    reminderBusy: Boolean,
    reminderCooldown: Long,
    onReminder: () -> Unit,
    journeySummary: JourneySummary,
    onOpenJourney: () -> Unit,
    onOpenHealth: () -> Unit,
    onGoogleMaps: () -> Unit,
    onDirections: () -> Unit,
    firebaseError: String?,
    usingFallback: Boolean,
    deviceFromCache: Boolean,
    locationEnabled: Boolean?,
    fineLocationGranted: Boolean?
) {
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).background(connectionColor(assessment.level), RoundedCornerShape(50)))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(assessment.title, color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text(assessment.detail, color = Color(0xFF94AAB8), style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                when {
                    seen <= 0L -> "Chưa nhận được vị trí GPS"
                    else -> "Vị trí ${age(seen)} trước · ±${accuracy.toInt()} m"
                },
                color = if (seen > 0 && System.currentTimeMillis() - seen <= 10 * 60_000L) Color(0xFFC7D7E0) else Color(0xFFFFC857),
                style = MaterialTheme.typography.bodyMedium
            )
        }

        if (confirmedLocationOff) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2C2413)),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text("Vị trí trên Máy Con đang tắt", color = Color(0xFFFFC857), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text("Xác nhận lúc ${fmt(locationDiagnosticAt)}. Lời nhắc chỉ được gửi khi bạn bấm nút bên dưới.", color = Color(0xFFD8C79C), style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = onReminder,
                            enabled = !reminderBusy && reminderCooldown <= 0L,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                when {
                                    reminderBusy -> "ĐANG GỬI LỜI NHẮC..."
                                    reminderCooldown > 0L -> "CÓ THỂ NHẮC LẠI SAU ${maxOf(1L, reminderCooldown / 1000L)}S"
                                    else -> "NHẮC BẬT VỊ TRÍ"
                                }
                            )
                        }
                        if (reminderText.isNotBlank()) {
                            Spacer(Modifier.height(7.dp))
                            Text(reminderText, color = Color(0xFFBFCED6), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth().height(340.dp), shape = RoundedCornerShape(22.dp)) {
                OsmMap(lat, lon, accuracy, dayEvents, true, savedState, onMapViewCreated)
            }
        }
        item { Text("Bản đồ: © OpenStreetMap contributors", color = Color(0xFF697F8C), style = MaterialTheme.typography.labelSmall) }

        item {
            Button(onClick = onRefresh, enabled = !refreshBusy, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text(if (refreshBusy) "ĐANG CẬP NHẬT..." else "Cập nhật vị trí")
            }
            Spacer(Modifier.height(6.dp))
            Text(refreshText, color = Color(0xFF91A6B3), style = MaterialTheme.typography.bodySmall)
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onGoogleMaps, enabled = lat != null && lon != null, modifier = Modifier.weight(1f)) { Text("Google Maps") }
                OutlinedButton(onClick = onDirections, enabled = lat != null && lon != null, modifier = Modifier.weight(1f)) { Text("Dẫn đường") }
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusMiniCard("Kết nối", assessment.title, connectionColor(assessment.level), Modifier.weight(1f))
                StatusMiniCard("GPS", when (locationEnabled) { true -> "Bật"; false -> "Tắt"; else -> "Chưa rõ" }, if (locationEnabled == true) Color(0xFF76D79A) else Color(0xFFFFC857), Modifier.weight(1f))
                StatusMiniCard("Dữ liệu", if (usingFallback) "Dự phòng" else if (deviceFromCache) "Lưu tạm" else "Trực tiếp", if (deviceFromCache && !usingFallback) Color(0xFFFFC857) else Color(0xFF76D79A), Modifier.weight(1f))
            }
        }

        item {
            Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF0F1720))) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Hành trình hôm nay", color = Color.White, style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = onOpenJourney) { Text("Xem chi tiết") }
                    }
                    Text("${journeySummary.stops} điểm dừng · ${formatDistance(journeySummary.distanceMeters)} · ${journeySummary.validPoints} điểm GPS hợp lệ", color = Color(0xFFA6BAC6), style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        if (firebaseError != null || deviceFromCache || fineLocationGranted == false) {
            item {
                Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF171A20))) {
                    Column(Modifier.fillMaxWidth().padding(15.dp)) {
                        Text("Cần kiểm tra", color = Color(0xFFFFC857), style = MaterialTheme.typography.titleSmall)
                        if (firebaseError != null) Text(firebaseError, color = Color(0xFFD0BBC0), style = MaterialTheme.typography.bodySmall)
                        if (deviceFromCache) Text("Dữ liệu realtime đang gián đoạn hoặc từ cache; ứng dụng đang thử HTTPS dự phòng.", color = Color(0xFFB7C4CC), style = MaterialTheme.typography.bodySmall)
                        if (fineLocationGranted == false) Text("Máy Con chưa cấp quyền Vị trí chính xác.", color = Color(0xFFB7C4CC), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = onOpenHealth, contentPadding = PaddingValues(0.dp)) { Text("MỞ SỨC KHỎE KẾT NỐI") }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusMiniCard(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Card(modifier, shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF0E161F))) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 12.dp)) {
            Text(label, color = Color(0xFF718896), style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(4.dp))
            Text(value, color = color, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

@Composable
private fun JourneyScreen(
    modifier: Modifier,
    selectedDayStart: Long,
    onSelectDay: (Long) -> Unit,
    isToday: Boolean,
    dayEvents: List<UiEvent>,
    visits: List<UiVisit>,
    summary: JourneySummary,
    lat: Double?,
    lon: Double?,
    accuracy: Double,
    savedState: Bundle?,
    onMapViewCreated: (MapView) -> Unit,
    onOpenPoint: (Double, Double) -> Unit
) {
    val context = LocalContext.current
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("Hành trình", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text(if (isToday) "Hôm nay" else formatDate(selectedDayStart), color = Color(0xFF91A6B3), style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(onClick = {
                    val c = Calendar.getInstance().apply { timeInMillis = selectedDayStart }
                    DatePickerDialog(context, { _, y, m, d -> onSelectDay(startOfDate(y, m, d)) }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).apply {
                        datePicker.maxDate = System.currentTimeMillis()
                        show()
                    }
                }) { Text("Chọn ngày") }
            }
            if (!isToday) TextButton(onClick = { onSelectDay(startOfToday()) }, contentPadding = PaddingValues(0.dp)) { Text("Quay về hôm nay") }
        }

        item {
            Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF0F1720))) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("Tổng quan", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(10.dp))
                    HealthRow("Quãng đường ghi nhận", formatDistance(summary.distanceMeters))
                    HealthRow("Điểm GPS hợp lệ", summary.validPoints.toString())
                    HealthRow("Điểm dừng", summary.stops.toString())
                    HealthRow("Thời gian di chuyển", formatDuration(summary.movingMs))
                    HealthRow("Thời gian dừng", formatDuration(summary.stoppedMs))
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth().height(300.dp), shape = RoundedCornerShape(22.dp)) {
                OsmMap(lat, lon, accuracy, dayEvents, isToday, savedState, onMapViewCreated)
            }
        }

        when {
            dayEvents.isEmpty() -> item { Text("Ngày này chưa có dữ liệu vị trí.", color = Color(0xFF91A6B3)) }
            visits.isNotEmpty() -> {
                item { Text("Các điểm dừng", color = Color.White, style = MaterialTheme.typography.titleMedium) }
                items(visits) { visit -> VisitCard(visit, onOpenPoint) }
            }
            else -> {
                item { Text("Các điểm GPS đã xác nhận", color = Color.White, style = MaterialTheme.typography.titleMedium) }
                items(validRouteEvents(dayEvents).takeLast(50).reversed()) { sample ->
                    Card(shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.fillMaxWidth().padding(14.dp)) {
                            Text("Điểm ghi nhận ${fmt(sample.time)}", color = Color.White)
                            Text("${sample.lat ?: 0.0}, ${sample.lon ?: 0.0}${sample.accuracy?.let { " · ±${it.toInt()} m" } ?: ""}", color = Color(0xFFABC0CB), style = MaterialTheme.typography.bodySmall)
                            if (sample.lat != null && sample.lon != null) TextButton(onClick = { onOpenPoint(sample.lat, sample.lon) }, contentPadding = PaddingValues(0.dp)) { Text("Xem trên Google Maps") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VisitCard(visit: UiVisit, openMap: (Double, Double) -> Unit) {
    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF0F1720))) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.size(32.dp).background(Color(0xFF153442), RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                Text(visit.index.toString(), color = Color(0xFF55D8FF), fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Địa điểm ${visit.index}", color = Color.White, style = MaterialTheme.typography.titleMedium)
                Text("Đến ${fmt(visit.arrival)} · Rời ${visit.departure?.let(::fmt) ?: "Hiện tại"}", color = Color(0xFFB6C8D2), style = MaterialTheme.typography.bodySmall)
                Text("Dừng ${formatDuration(visit.durationMs)}${visit.accuracy?.let { " · ±${it.toInt()} m" } ?: ""}", color = Color(0xFF8CA3AF), style = MaterialTheme.typography.bodySmall)
                if (visit.lat != null && visit.lon != null) TextButton(onClick = { openMap(visit.lat, visit.lon) }, contentPadding = PaddingValues(0.dp)) { Text("Xem điểm này trên Google Maps") }
            }
        }
    }
}

@Composable
private fun HealthScreen(
    modifier: Modifier,
    assessment: ConnectionAssessment,
    now: Long,
    networkType: String?,
    networkValidated: Boolean?,
    networkAt: Long,
    firebaseRecent: Boolean,
    firebaseAt: Long,
    firebaseLatencyMs: Long,
    httpsRecent: Boolean,
    httpsAt: Long,
    httpsLatencyMs: Long,
    locationEnabled: Boolean?,
    fineLocationGranted: Boolean?,
    backgroundLocationGranted: Boolean?,
    notificationGranted: Boolean?,
    diagnosticAt: Long,
    serviceState: String,
    serviceStartedAt: Long,
    childAppVersion: String?,
    lastError: String?,
    parentRealtimeOk: Boolean,
    parentHttpsOk: Boolean,
    parentHttpsLatencyMs: Long
) {
    val lastServer = maxOf(firebaseAt, httpsAt, diagnosticAt)
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text("Sức khỏe kết nối", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF0F1720))) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(14.dp).background(connectionColor(assessment.level), RoundedCornerShape(50)))
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(assessment.title, color = Color.White, style = MaterialTheme.typography.titleMedium)
                        Text(assessment.detail, color = Color(0xFF99AFBB), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        item {
            HealthCard("Kết nối Máy Con") {
                HealthRow("Mạng", networkLabel(networkType))
                HealthRow("Internet được Android xác nhận", yesNoUnknown(networkValidated))
                HealthRow("Trạng thái mạng cập nhật", if (networkAt > 0L) "${ageAt(now, networkAt)} trước" else "Chưa có dữ liệu")
                HealthRow("Firebase realtime", if (firebaseRecent) "Hoạt động" else if (firebaseAt > 0L) "Không phản hồi gần đây" else "Chưa xác định")
                HealthRow("Firebase gần nhất", if (firebaseAt > 0L) "${ageAt(now, firebaseAt)} trước" else "Chưa có dữ liệu")
                HealthRow("Độ trễ Firebase", if (firebaseLatencyMs > 0L) "${firebaseLatencyMs} ms" else "Chưa đo được")
                HealthRow("HTTPS dự phòng", if (httpsRecent) "Hoạt động" else if (httpsAt > 0L) "Không phản hồi gần đây" else "Chưa xác định")
                HealthRow("HTTPS gần nhất", if (httpsAt > 0L) "${ageAt(now, httpsAt)} trước" else "Chưa có dữ liệu")
                HealthRow("Độ trễ HTTPS", if (httpsLatencyMs > 0L) "${httpsLatencyMs} ms" else "Chưa đo được")
                HealthRow("Liên lạc server gần nhất", if (lastServer > 0L) "${ageAt(now, lastServer)} trước" else "Chưa có dữ liệu")
            }
        }

        item {
            HealthCard("Vị trí") {
                HealthRow("GPS / Location", when (locationEnabled) { true -> "Bật"; false -> "Tắt"; else -> "Chưa xác định" })
                HealthRow("Vị trí chính xác", yesNoUnknown(fineLocationGranted))
                HealthRow("Vị trí nền 'Mọi lúc'", yesNoUnknown(backgroundLocationGranted))
                HealthRow("Trạng thái xác nhận", if (diagnosticAt > 0L) "${ageAt(now, diagnosticAt)} trước" else "Chưa có dữ liệu")
            }
        }

        item {
            HealthCard("Ứng dụng Máy Con") {
                HealthRow("Dịch vụ", serviceStateLabel(serviceState))
                HealthRow("Khởi động dịch vụ", if (serviceStartedAt > 0L) "${ageAt(now, serviceStartedAt)} trước" else "Chưa có dữ liệu")
                HealthRow("Quyền thông báo", yesNoUnknown(notificationGranted))
                HealthRow("Phiên bản", childAppVersion ?: "Chưa xác định")
                if (!lastError.isNullOrBlank()) HealthRow("Thông báo cuối", lastError)
            }
        }

        item {
            HealthCard("Kết nối trên Máy Cha") {
                HealthRow("Firestore realtime", if (parentRealtimeOk) "Hoạt động" else "Đang gián đoạn / cache")
                HealthRow("HTTPS dự phòng", if (parentHttpsOk) "Hoạt động" else "Chưa hoạt động gần đây")
                HealthRow("Độ trễ HTTPS", if (parentHttpsLatencyMs >= 0L) "${parentHttpsLatencyMs} ms" else "Chưa đo được")
            }
        }

        item {
            Text(
                "Khi Máy Con mất liên lạc hoàn toàn, ứng dụng chỉ nêu trạng thái cuối cùng đã xác nhận và không đoán chắc rằng app đã bị dừng hay Internet đã mất.",
                color = Color(0xFF718995),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun HealthCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF0F1720))) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun HealthRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
        Text(label, modifier = Modifier.weight(1f), color = Color(0xFF8299A6), style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.width(12.dp))
        Text(value, modifier = Modifier.weight(1f), color = Color(0xFFC8D7DF), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.End)
    }
}

@Composable
private fun SettingsScreen(modifier: Modifier, childAppVersion: String?, onHealth: () -> Unit) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text("Cài đặt", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text("Kết Nối · phiên bản 2.2.0", color = Color(0xFF8CA2AF), style = MaterialTheme.typography.bodySmall)
        }
        item {
            HealthCard("Thiết bị") {
                HealthRow("Mô hình sử dụng", "1 Máy Cha · 1 Máy Con")
                HealthRow("Máy Con", "Thiết bị chính")
                HealthRow("App Máy Con", childAppVersion ?: "Chưa xác định")
            }
        }
        item {
            HealthCard("Cảnh báo kết nối") {
                HealthRow("Cần chú ý", "Sau 10 phút không có tín hiệu")
                HealthRow("Mất kết nối", "Sau 15 phút không có tín hiệu")
                TextButton(onClick = onHealth, contentPadding = PaddingValues(0.dp)) { Text("MỞ SỨC KHỎE KẾT NỐI") }
            }
        }
        item {
            Text("Ứng dụng không hỗ trợ nhiều Máy Con trong phiên bản này.", color = Color(0xFF718995), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun OsmMap(
    currentLat: Double?,
    currentLon: Double?,
    accuracyM: Double,
    events: List<UiEvent>,
    showCurrent: Boolean,
    savedState: Bundle?,
    onMapViewCreated: (MapView) -> Unit
) {
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var renderedKey by remember { mutableIntStateOf(Int.MIN_VALUE) }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            MapView(context).also { view ->
                view.onCreate(savedState)
                onMapViewCreated(view)
                view.getMapAsync { readyMap ->
                    map = readyMap
                    readyMap.uiSettings.apply {
                        isZoomGesturesEnabled = true
                        isScrollGesturesEnabled = true
                        isRotateGesturesEnabled = true
                        isTiltGesturesEnabled = false
                    }
                    readyMap.setStyle(Style.Builder().fromUri(OSM_STYLE)) {
                        showMapData(readyMap, currentLat, currentLon, accuracyM, events, showCurrent)
                        renderedKey = mapKey(currentLat, currentLon, accuracyM, events, showCurrent)
                    }
                }
            }
        },
        update = {
            val current = map
            val key = mapKey(currentLat, currentLon, accuracyM, events, showCurrent)
            if (current != null && key != renderedKey) {
                showMapData(current, currentLat, currentLon, accuracyM, events, showCurrent)
                renderedKey = key
            }
        }
    )
}

private fun mapKey(lat: Double?, lon: Double?, accuracy: Double, events: List<UiEvent>, showCurrent: Boolean): Int =
    listOf(lat, lon, accuracy.toInt(), events.hashCode(), showCurrent).hashCode()

@Suppress("DEPRECATION")
private fun showMapData(map: MapLibreMap, lat: Double?, lon: Double?, accuracyM: Double, events: List<UiEvent>, showCurrent: Boolean) {
    map.clear()
    val points = validRouteEvents(events).map { LatLng(it.lat!!, it.lon!!) }
    if (points.size >= 2) map.addPolyline(PolylineOptions().addAll(points).width(5f))
    events.filter { it.type == "visit_start" && it.lat != null && it.lon != null }.sortedBy { it.time }.forEachIndexed { index, e ->
        map.addMarker(MarkerOptions().position(LatLng(e.lat!!, e.lon!!)).title("Điểm dừng ${index + 1}").snippet(fmt(e.time)))
    }
    when {
        showCurrent && lat != null && lon != null -> {
            val latest = LatLng(lat, lon)
            if (accuracyM in 1.0..1000.0) {
                map.addPolygon(
                    PolygonOptions()
                        .addAll(accuracyCircle(lat, lon, accuracyM))
                        .fillColor(android.graphics.Color.argb(42, 85, 216, 255))
                        .strokeColor(android.graphics.Color.argb(190, 85, 216, 255))
                )
            }
            map.addMarker(MarkerOptions().position(latest).title("Vị trí mới nhất").snippet("±${accuracyM.toInt()} m"))
            map.cameraPosition = CameraPosition.Builder().target(latest).zoom(16.0).build()
        }
        points.isNotEmpty() -> map.cameraPosition = CameraPosition.Builder().target(points.last()).zoom(15.0).build()
        else -> map.cameraPosition = CameraPosition.Builder().target(LatLng(16.0, 106.0)).zoom(5.0).build()
    }
}

private fun accuracyCircle(lat: Double, lon: Double, radiusM: Double, segments: Int = 64): List<LatLng> {
    val earthRadius = 6_371_000.0
    val latRad = Math.toRadians(lat)
    return (0..segments).map { i ->
        val bearing = 2.0 * Math.PI * i / segments
        val angular = radiusM / earthRadius
        val lat2 = asin(sin(latRad) * cos(angular) + cos(latRad) * sin(angular) * cos(bearing))
        val lon2 = Math.toRadians(lon) + atan2(
            sin(bearing) * sin(angular) * cos(latRad),
            cos(angular) - sin(latRad) * sin(lat2)
        )
        LatLng(Math.toDegrees(lat2), Math.toDegrees(lon2))
    }
}

private fun assessConnection(
    now: Long,
    seen: Long,
    heartbeatAt: Long,
    diagnosticAt: Long,
    networkValidated: Boolean?,
    networkAt: Long,
    firebaseRealtimeOkAt: Long,
    firebaseWriteOkAt: Long,
    httpsFallbackOkAt: Long,
    serviceState: String,
    locationEnabled: Boolean?
): ConnectionAssessment {
    val lastContact = maxOf(seen, heartbeatAt, diagnosticAt, firebaseRealtimeOkAt, firebaseWriteOkAt, httpsFallbackOkAt)
    if (lastContact <= 0L) return ConnectionAssessment(ConnectionLevel.UNKNOWN, "Chưa có kết nối", "Chưa nhận được trạng thái từ Máy Con.", 0L)
    val silence = (now - lastContact).coerceAtLeast(0L)
    if (silence <= CONNECTION_WARN_MS) {
        return if (locationEnabled == false && diagnosticAt > 0L && now - diagnosticAt <= DIAGNOSTIC_FRESH_MS) {
            ConnectionAssessment(ConnectionLevel.WARNING, "Cần chú ý", "Máy Con đang kết nối nhưng Vị trí đang tắt.", lastContact)
        } else {
            ConnectionAssessment(ConnectionLevel.CONNECTED, "Đang kết nối", "Tín hiệu gần nhất ${ageAt(now, lastContact)} trước.", lastContact)
        }
    }

    val detail = when {
        networkValidated == false && networkAt > 0L && lastContact - networkAt < DIAGNOSTIC_FRESH_MS ->
            "Trạng thái cuối cho thấy Internet trên Máy Con chưa được xác nhận."
        serviceState == "permission_missing" -> "Trạng thái cuối cho thấy Máy Con thiếu quyền Vị trí chính xác."
        serviceState == "location_off" || serviceState == "attention_needed" && locationEnabled == false ->
            "Trạng thái cuối cho thấy Vị trí trên Máy Con đang tắt."
        networkValidated == true ->
            "Máy Con từng có Internet nhưng chưa liên lạc lại máy chủ; chưa thể xác định chắc mạng hay ứng dụng bị gián đoạn."
        else -> "Không nhận được tín hiệu mới; chưa thể xác định chắc nguyên nhân mất liên lạc."
    }
    return if (silence <= CONNECTION_LOST_MS) {
        ConnectionAssessment(ConnectionLevel.WARNING, "Cần chú ý", "$detail Không có tín hiệu ${ageAt(now, lastContact)}.", lastContact)
    } else {
        ConnectionAssessment(ConnectionLevel.LOST, "Mất kết nối", "$detail Tín hiệu cuối ${ageAt(now, lastContact)} trước.", lastContact)
    }
}

private fun validRouteEvents(events: List<UiEvent>): List<UiEvent> {
    val samples = events.filter { it.type == "location_sample" && it.lat != null && it.lon != null && (it.accuracy == null || it.accuracy <= 150.0) }.sortedBy { it.time }
    if (samples.isNotEmpty()) return samples
    return events.filter { it.type == "trip_point" && it.lat != null && it.lon != null && (it.accuracy == null || it.accuracy <= 150.0) }.sortedBy { it.time }
}

private fun calculateJourneySummary(events: List<UiEvent>, visits: List<UiVisit>, dayStart: Long, dayEnd: Long): JourneySummary {
    val route = validRouteEvents(events)
    var distance = 0.0
    for (i in 1 until route.size) {
        val a = route[i - 1]
        val b = route[i]
        distance += haversineMeters(a.lat!!, a.lon!!, b.lat!!, b.lon!!)
    }
    val tripStarts = events.filter { it.type == "trip_start" }.associateBy { it.id }
    val tripEnds = events.filter { it.type == "trip_end" }.associateBy { it.id }
    val now = System.currentTimeMillis()
    var moving = 0L
    tripStarts.values.forEach { start ->
        val end = tripEnds[start.id]?.time ?: minOf(dayEnd, now)
        val s = maxOf(start.time, dayStart)
        val e = minOf(end, dayEnd, now)
        if (e > s) moving += e - s
    }
    val stopped = visits.sumOf { it.durationMs }
    return JourneySummary(distance, route.size, visits.size, moving, stopped)
}

fun toVisits(events: List<UiEvent>, dayStart: Long, dayEnd: Long): List<UiVisit> {
    val starts = events.filter { it.type == "visit_start" }.associateBy { it.id }
    val ends = events.filter { it.type == "visit_end" }.associateBy { it.id }
    val now = System.currentTimeMillis()
    val chronological = starts.values.sortedBy { it.time }
    return chronological.mapIndexedNotNull { index, s ->
        val rawEnd = ends[s.id]?.time ?: minOf(dayEnd, now)
        val visibleStart = maxOf(s.time, dayStart)
        val visibleEnd = minOf(rawEnd, dayEnd, now)
        if (visibleEnd <= visibleStart) null else UiVisit(
            index = index + 1,
            arrival = visibleStart,
            departure = ends[s.id]?.time?.takeIf { it <= dayEnd && it <= now },
            durationMs = visibleEnd - visibleStart,
            lat = s.lat,
            lon = s.lon,
            accuracy = s.accuracy
        )
    }
}

private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6_371_000.0
    val p1 = Math.toRadians(lat1)
    val p2 = Math.toRadians(lat2)
    val dp = Math.toRadians(lat2 - lat1)
    val dl = Math.toRadians(lon2 - lon1)
    val a = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
    return 2 * r * atan2(sqrt(a), sqrt(1 - a))
}

private fun notifyConnectionIssue(context: Context, title: String, message: String) {
    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
    val nm = context.getSystemService(NotificationManager::class.java)
    val channelId = "connection_health"
    if (Build.VERSION.SDK_INT >= 26) {
        nm.createNotificationChannel(NotificationChannel(channelId, "Sức khỏe kết nối", NotificationManager.IMPORTANCE_HIGH))
    }
    val open = PendingIntent.getActivity(
        context,
        501,
        Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
    nm.notify(
        501,
        NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_guardian_shield_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
    )
}

private fun openGoogleMapsPoint(context: Context, lat: Double, lon: Double) {
    val uri = Uri.parse("https://www.google.com/maps/search/?api=1&query=$lat,$lon")
    val appIntent = Intent(Intent.ACTION_VIEW, uri).apply { setPackage("com.google.android.apps.maps") }
    try { context.startActivity(appIntent) } catch (_: ActivityNotFoundException) { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
}

private fun openGoogleMapsDirections(context: Context, lat: Double, lon: Double) {
    val uri = Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$lat,$lon")
    val appIntent = Intent(Intent.ACTION_VIEW, uri).apply { setPackage("com.google.android.apps.maps") }
    try { context.startActivity(appIntent) } catch (_: ActivityNotFoundException) { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
}

private fun tabLabel(tab: ParentTab): String = when (tab) {
    ParentTab.HOME -> "Trang chủ"
    ParentTab.JOURNEY -> "Hành trình"
    ParentTab.HEALTH -> "Sức khỏe"
    ParentTab.SETTINGS -> "Cài đặt"
}

private fun tabIcon(tab: ParentTab): String = when (tab) {
    ParentTab.HOME -> "⌂"
    ParentTab.JOURNEY -> "↝"
    ParentTab.HEALTH -> "●"
    ParentTab.SETTINGS -> "⚙"
}

private fun connectionColor(level: ConnectionLevel): Color = when (level) {
    ConnectionLevel.CONNECTED -> Color(0xFF70D894)
    ConnectionLevel.WARNING -> Color(0xFFFFC857)
    ConnectionLevel.LOST -> Color(0xFFFF7777)
    ConnectionLevel.UNKNOWN -> Color(0xFF8195A1)
}

private fun networkLabel(type: String?): String = when (type) {
    "wifi" -> "Wi-Fi"
    "4g" -> "4G"
    "5g" -> "5G"
    "cellular" -> "Dữ liệu di động (4G/5G)"
    "ethernet" -> "Ethernet"
    "vpn" -> "VPN"
    "none" -> "Không có mạng"
    "other" -> "Mạng khác"
    else -> "Chưa xác định"
}

private fun serviceStateLabel(state: String): String = when (state) {
    "tracking" -> "Đang chạy"
    "attention_needed" -> "Cần chú ý"
    "location_off" -> "Đang chạy · Location tắt"
    "permission_missing" -> "Thiếu quyền Vị trí"
    "unknown" -> "Chưa xác định"
    else -> state
}

private fun yesNoUnknown(value: Boolean?): String = when (value) { true -> "Có"; false -> "Không"; null -> "Chưa xác định" }
private fun ageAt(now: Long, t: Long): String {
    val s = ((now - t) / 1000L).coerceAtLeast(0L)
    return when {
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60} phút"
        s < 86400 -> "${s / 3600} giờ"
        else -> "${s / 86400} ngày"
    }
}
fun age(t: Long): String = ageAt(System.currentTimeMillis(), t)
fun fmt(t: Long): String = SimpleDateFormat("HH:mm", Locale("vi", "VN")).format(Date(t))
fun formatDate(t: Long): String = SimpleDateFormat("dd/MM/yyyy", Locale("vi", "VN")).format(Date(t))
private fun formatDistance(meters: Double): String = if (meters < 1000.0) "${meters.roundToInt()} m" else String.format(Locale("vi", "VN"), "%.1f km", meters / 1000.0)
private fun formatDuration(ms: Long): String {
    val totalMin = (ms / 60_000L).coerceAtLeast(0L)
    val h = totalMin / 60L
    val m = totalMin % 60L
    return if (h > 0) "${h}h ${m}p" else "${m} phút"
}
fun startOfToday(): Long {
    val c = Calendar.getInstance()
    c.set(Calendar.HOUR_OF_DAY, 0)
    c.set(Calendar.MINUTE, 0)
    c.set(Calendar.SECOND, 0)
    c.set(Calendar.MILLISECOND, 0)
    return c.timeInMillis
}
fun startOfDate(year: Int, month: Int, day: Int): Long = Calendar.getInstance().apply {
    set(Calendar.YEAR, year)
    set(Calendar.MONTH, month)
    set(Calendar.DAY_OF_MONTH, day)
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis
