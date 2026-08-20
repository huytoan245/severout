package com.family.parent

import android.app.DatePickerDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.MarkerOptions
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

private const val CHILD_DOC = "child-01"
private const val OSM_STYLE = "asset://osm_raster_style.json"

data class UiEvent(val type: String = "", val time: Long = 0L, val lat: Double? = null, val lon: Double? = null, val accuracy: Double? = null, val id: String = "")
data class UiVisit(val title: String, val detail: String, val lat: Double?, val lon: Double?)

class MainActivity : ComponentActivity() {
    private var mapView: MapView? = null
    private var restoredState: Bundle? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restoredState = savedInstanceState
        MapLibre.getInstance(this)
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
    MaterialTheme(colorScheme = darkColorScheme()) {
        when {
            authError != null -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { Text("Không đăng nhập được Firebase: $authError", color = MaterialTheme.colorScheme.error) }
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
    var lat by remember { mutableStateOf<Double?>(null) }
    var lon by remember { mutableStateOf<Double?>(null) }
    var seen by remember { mutableLongStateOf(0L) }
    var acc by remember { mutableDoubleStateOf(0.0) }
    var heartbeatAt by remember { mutableLongStateOf(0L) }
    var locationEnabled by remember { mutableStateOf<Boolean?>(null) }
    var fineLocationGranted by remember { mutableStateOf<Boolean?>(null) }
    var backgroundLocationGranted by remember { mutableStateOf<Boolean?>(null) }
    var serviceState by remember { mutableStateOf("unknown") }
    var lastError by remember { mutableStateOf<String?>(null) }
    var events by remember { mutableStateOf(listOf<UiEvent>()) }
    var firebaseError by remember { mutableStateOf<String?>(null) }
    var deviceFromCache by remember { mutableStateOf(false) }
    var eventFromCache by remember { mutableStateOf(false) }
    var refreshText by remember { mutableStateOf("Sẵn sàng cập nhật vị trí") }
    var refreshRequestId by remember { mutableLongStateOf(0L) }
    var commandConfirmedFor by remember { mutableLongStateOf(0L) }
    var selectedDayStart by remember { mutableLongStateOf(startOfToday()) }
    var lastServerSnapshotAt by remember { mutableLongStateOf(0L) }
    var lastHttpsSuccessAt by remember { mutableLongStateOf(0L) }
    var httpsFallbackActive by remember { mutableStateOf(false) }

    fun processRefreshState(ack: Long, completed: Long, failed: Long, error: String?) {
        val request = refreshRequestId
        if (request <= 0L) return
        when {
            completed == request -> {
                refreshText = "Đã nhận vị trí mới lúc ${if (seen > 0) fmt(seen) else fmt(System.currentTimeMillis())}"
                refreshRequestId = 0L
            }
            failed == request -> {
                refreshText = "Máy con không cập nhật được: ${error ?: "không lấy được GPS"}"
                refreshRequestId = 0L
            }
            ack == request -> refreshText = "Máy con đã nhận yêu cầu · đang lấy GPS..."
        }
    }

    fun applyRestState(s: ParentHttpsBridge.DeviceState) {
        if (s.lastLat != null) lat = s.lastLat
        if (s.lastLon != null) lon = s.lastLon
        if (s.accuracy != null) acc = s.accuracy
        if (s.lastSeen > 0L) seen = s.lastSeen
        if (s.heartbeatAt > 0L) heartbeatAt = s.heartbeatAt
        if (s.locationEnabled != null) locationEnabled = s.locationEnabled
        if (s.fineLocationGranted != null) fineLocationGranted = s.fineLocationGranted
        if (s.backgroundLocationGranted != null) backgroundLocationGranted = s.backgroundLocationGranted
        serviceState = s.serviceState ?: s.status ?: serviceState
        lastError = s.lastError
        processRefreshState(s.refreshAckFor, s.refreshCompletedFor, s.refreshFailedFor, s.lastError)
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
                locationEnabled = d.getBoolean("locationEnabled")
                fineLocationGranted = d.getBoolean("fineLocationGranted")
                backgroundLocationGranted = d.getBoolean("backgroundLocationGranted")
                serviceState = d.getString("serviceState") ?: d.getString("status") ?: "unknown"
                lastError = d.getString("lastError")?.takeIf { it.isNotBlank() }
                processRefreshState(
                    d.getLong("refreshAckFor") ?: 0L,
                    d.getLong("refreshCompletedFor") ?: 0L,
                    d.getLong("refreshFailedFor") ?: 0L,
                    lastError
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
                    if (deviceFromCache) refreshText = if (refreshRequestId > 0) "Mạng đã trở lại · đang nối lại máy chủ..." else refreshText
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
            delay(15_000L)
            val now = System.currentTimeMillis()
            val serverStale = lastServerSnapshotAt == 0L || now - lastServerSnapshotAt > 25_000L
            if (deviceFromCache || serverStale || refreshRequestId > 0L) {
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
                    refreshText = "Chưa xác nhận được lệnh qua Firebase · đang thử HTTPS dự phòng..."
                }
            }
            if (refreshRequestId == request) refreshText = "Máy con chưa phản hồi sau 45 giây"
        }
    }

    val dayEnd = selectedDayStart + 24L * 60 * 60 * 1000
    val dayEvents = remember(events, selectedDayStart) { events.filter { it.time in selectedDayStart until dayEnd } }
    val visits = remember(dayEvents, selectedDayStart) { toVisits(dayEvents, selectedDayStart, dayEnd) }
    val isToday = selectedDayStart == startOfToday()
    val now = System.currentTimeMillis()
    val staleLocation = seen > 0 && now - seen > 10 * 60_000L
    val heartbeatRecent = heartbeatAt > 0 && now - heartbeatAt <= 10 * 60_000L
    val locationRecent = seen > 0 && now - seen <= 2 * 60_000L
    val childOnline = heartbeatRecent || locationRecent
    val httpsRecent = lastHttpsSuccessAt > 0 && now - lastHttpsSuccessAt <= 45_000L

    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF55D8FF), background = Color(0xFF070A0F), surface = Color(0xFF111820))) {
        Scaffold(containerColor = MaterialTheme.colorScheme.background) { pad ->
            LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item {
                    Text("Guardian", style = MaterialTheme.typography.headlineMedium, color = Color.White)
                    Spacer(Modifier.height(3.dp))
                    when {
                        seen == 0L && childOnline -> Text("Máy con đang kết nối · chưa nhận được GPS", color = Color(0xFFFFC857))
                        seen == 0L -> Text("Chưa nhận được vị trí từ máy con", color = Color(0xFFFFC857))
                        staleLocation -> Text("Vị trí cuối ${age(seen)} trước · dữ liệu đã cũ · ±${acc.toInt()} m", color = Color(0xFFFFC857))
                        else -> Text("Vị trí cập nhật ${age(seen)} trước · ±${acc.toInt()} m", color = Color(0xFFA8BAC7))
                    }
                    Text(
                        if (childOnline) "Kết nối máy con: đang hoạt động" else "Kết nối máy con: chưa có tín hiệu gần đây",
                        color = if (childOnline) Color(0xFF8BD6A5) else Color(0xFFA8BAC7),
                        style = MaterialTheme.typography.bodySmall
                    )
                    when {
                        httpsFallbackActive && httpsRecent -> Text("Kênh HTTPS dự phòng đang hoạt động.", color = Color(0xFF8BD6A5), style = MaterialTheme.typography.bodySmall)
                        deviceFromCache || eventFromCache -> Text("Đang hiển thị dữ liệu lưu tạm; app sẽ tự thử kênh HTTPS dự phòng.", color = Color(0xFFFFC857), style = MaterialTheme.typography.bodySmall)
                    }
                }

                if (firebaseError != null && !(httpsFallbackActive && httpsRecent)) item {
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF32171A))) {
                        Column(Modifier.fillMaxWidth().padding(14.dp)) {
                            Text("Firebase chưa kết nối được", color = Color(0xFFFF8A8A), style = MaterialTheme.typography.titleSmall)
                            Text(firebaseError ?: "", color = Color(0xFFFFB0B0))
                            Text("Ứng dụng sẽ tự thử lại qua HTTPS thông thường nếu mạng hiện tại làm gián đoạn kênh realtime.", color = Color(0xFFFFC857), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                if (locationEnabled == false || fineLocationGranted == false || serviceState == "attention_needed" || lastError != null) item {
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF2B2516))) {
                        Column(Modifier.fillMaxWidth().padding(14.dp)) {
                            if (locationEnabled == false) Text("Vị trí trên máy con đang tắt.", color = Color(0xFFFFC857))
                            if (fineLocationGranted == false) Text("Máy con chưa cấp vị trí chính xác.", color = Color(0xFFFFC857))
                            if (backgroundLocationGranted == false) Text("Máy con chưa cấp vị trí 'Mọi lúc'; tự khôi phục sau reboot có thể bị hạn chế.", color = Color(0xFFFFC857), style = MaterialTheme.typography.bodySmall)
                            if (lastError != null) Text(lastError ?: "", color = Color(0xFFFFC857), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                item { Card(Modifier.fillMaxWidth().height(360.dp)) { OsmMap(lat, lon, dayEvents, isToday, savedState, onMapViewCreated) } }
                item { Text("Bản đồ: © OpenStreetMap contributors", color = Color(0xFF8799A6), style = MaterialTheme.typography.labelSmall) }

                item {
                    Button(
                        onClick = {
                            if (refreshRequestId > 0L) return@Button
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
                                        refreshText = "Đã gửi yêu cầu · chờ máy con phản hồi..."
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
                                                refreshText = "Đã gửi qua HTTPS dự phòng · chờ máy con phản hồi..."
                                            } else if (commandConfirmedFor != requestAt) {
                                                refreshText = "Chưa gửi được lệnh: ${error ?: "mạng hiện tại không tới được máy chủ"}"
                                            }
                                        }
                                    }
                                }
                            }
                        },
                        enabled = refreshRequestId == 0L,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (refreshRequestId > 0L) "ĐANG CẬP NHẬT..." else "CẬP NHẬT VỊ TRÍ NGAY") }
                }
                item { Text(refreshText, color = Color(0xFFA8BAC7), style = MaterialTheme.typography.bodySmall) }

                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = { if (lat != null && lon != null) openGoogleMapsPoint(context, lat!!, lon!!) }, enabled = lat != null && lon != null, modifier = Modifier.weight(1f)) { Text("XEM GOOGLE MAPS") }
                        OutlinedButton(onClick = { if (lat != null && lon != null) openGoogleMapsDirections(context, lat!!, lon!!) }, enabled = lat != null && lon != null, modifier = Modifier.weight(1f)) { Text("DẪN ĐƯỜNG") }
                    }
                    if (lat == null || lon == null) {
                        Spacer(Modifier.height(5.dp))
                        Text("Sẽ bật ngay khi nhận được tọa độ thật từ máy con.", color = Color(0xFF8799A6), style = MaterialTheme.typography.bodySmall)
                    }
                }

                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(if (isToday) "Nhật ký hôm nay" else "Nhật ký ${formatDate(selectedDayStart)}", style = MaterialTheme.typography.titleLarge, color = Color.White)
                        OutlinedButton(onClick = {
                            val c = Calendar.getInstance().apply { timeInMillis = selectedDayStart }
                            val dialog = DatePickerDialog(context, { _, y, m, d -> selectedDayStart = startOfDate(y, m, d) }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH))
                            dialog.datePicker.maxDate = System.currentTimeMillis()
                            dialog.show()
                        }) { Text("CHỌN NGÀY") }
                    }
                    if (!isToday) TextButton(onClick = { selectedDayStart = startOfToday() }) { Text("Quay về hôm nay") }
                }

                when {
                    dayEvents.isEmpty() -> item { Text("Ngày này chưa có dữ liệu vị trí.", color = Color(0xFFA8BAC7)) }
                    visits.isNotEmpty() -> items(visits) { visit -> VisitCard(visit) { vLat, vLon -> openGoogleMapsPoint(context, vLat, vLon) } }
                    else -> items(dayEvents.filter { it.type == "location_sample" || it.type == "trip_point" }.sortedByDescending { it.time }.take(50)) { sample ->
                        Card { Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Text("Điểm ghi nhận ${fmt(sample.time)}", color = Color.White)
                            Text("${sample.lat ?: 0.0}, ${sample.lon ?: 0.0}${sample.accuracy?.let { " · ±${it.toInt()} m" } ?: ""}", color = Color(0xFFB7C8D5))
                            if (sample.lat != null && sample.lon != null) TextButton(onClick = { openGoogleMapsPoint(context, sample.lat, sample.lon) }, contentPadding = PaddingValues(0.dp)) { Text("XEM TRÊN GOOGLE MAPS") }
                        } }
                    }
                }
            }
        }
    }
}

@Composable
private fun VisitCard(visit: UiVisit, openMap: (Double, Double) -> Unit) {
    Card { Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(visit.title, color = Color.White, style = MaterialTheme.typography.titleMedium)
        Text(visit.detail, color = Color(0xFFB7C8D5))
        if (visit.lat != null && visit.lon != null) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { openMap(visit.lat, visit.lon) }, contentPadding = PaddingValues(0.dp)) { Text("XEM ĐIỂM NÀY TRÊN GOOGLE MAPS") }
        }
    } }
}

@Composable
private fun OsmMap(currentLat: Double?, currentLon: Double?, events: List<UiEvent>, showCurrent: Boolean, savedState: Bundle?, onMapViewCreated: (MapView) -> Unit) {
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var renderedKey by remember { mutableIntStateOf(Int.MIN_VALUE) }
    AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
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
                    showMapData(readyMap, currentLat, currentLon, events, showCurrent)
                    renderedKey = mapKey(currentLat, currentLon, events, showCurrent)
                }
            }
        }
    }, update = {
        val current = map
        val key = mapKey(currentLat, currentLon, events, showCurrent)
        if (current != null && key != renderedKey) {
            showMapData(current, currentLat, currentLon, events, showCurrent)
            renderedKey = key
        }
    })
}

private fun mapKey(lat: Double?, lon: Double?, events: List<UiEvent>, showCurrent: Boolean): Int = listOf(lat, lon, events.hashCode(), showCurrent).hashCode()

@Suppress("DEPRECATION")
private fun showMapData(map: MapLibreMap, lat: Double?, lon: Double?, events: List<UiEvent>, showCurrent: Boolean) {
    map.clear()
    val points = events.filter { (it.type == "location_sample" || it.type == "trip_point") && it.lat != null && it.lon != null }
        .sortedBy { it.time }
        .map { LatLng(it.lat!!, it.lon!!) }
    if (points.size >= 2) map.addPolyline(PolylineOptions().addAll(points).width(5f))
    events.filter { it.type == "visit_start" && it.lat != null && it.lon != null }.forEachIndexed { index, e ->
        map.addMarker(MarkerOptions().position(LatLng(e.lat!!, e.lon!!)).title("Điểm dừng ${index + 1}").snippet(fmt(e.time)))
    }
    when {
        showCurrent && lat != null && lon != null -> {
            val latest = LatLng(lat, lon)
            map.addMarker(MarkerOptions().position(latest).title("Vị trí mới nhất").snippet("$lat, $lon"))
            map.cameraPosition = CameraPosition.Builder().target(latest).zoom(16.0).build()
        }
        points.isNotEmpty() -> map.cameraPosition = CameraPosition.Builder().target(points.last()).zoom(15.0).build()
        else -> map.cameraPosition = CameraPosition.Builder().target(LatLng(16.0, 106.0)).zoom(5.0).build()
    }
}

private fun openGoogleMapsPoint(context: android.content.Context, lat: Double, lon: Double) {
    val uri = Uri.parse("https://www.google.com/maps/search/?api=1&query=$lat,$lon")
    val appIntent = Intent(Intent.ACTION_VIEW, uri).apply { setPackage("com.google.android.apps.maps") }
    try { context.startActivity(appIntent) } catch (_: ActivityNotFoundException) { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
}

private fun openGoogleMapsDirections(context: android.content.Context, lat: Double, lon: Double) {
    val uri = Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$lat,$lon")
    val appIntent = Intent(Intent.ACTION_VIEW, uri).apply { setPackage("com.google.android.apps.maps") }
    try { context.startActivity(appIntent) } catch (_: ActivityNotFoundException) { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
}

fun age(t: Long): String {
    val s = ((System.currentTimeMillis() - t) / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60} phút"
        s < 86400 -> "${s / 3600} giờ"
        else -> "${s / 86400} ngày"
    }
}
fun fmt(t: Long): String = SimpleDateFormat("HH:mm", Locale("vi", "VN")).format(Date(t))
fun formatDate(t: Long): String = SimpleDateFormat("dd/MM/yyyy", Locale("vi", "VN")).format(Date(t))
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

fun toVisits(events: List<UiEvent>, dayStart: Long, dayEnd: Long): List<UiVisit> {
    val starts = events.filter { it.type == "visit_start" }.associateBy { it.id }
    val ends = events.filter { it.type == "visit_end" }.associateBy { it.id }
    return starts.values.sortedByDescending { it.time }.mapNotNull { s ->
        val rawEnd = ends[s.id]?.time ?: if (dayEnd <= System.currentTimeMillis()) dayEnd else System.currentTimeMillis()
        val visibleStart = maxOf(s.time, dayStart)
        val visibleEnd = minOf(rawEnd, dayEnd, System.currentTimeMillis())
        if (visibleEnd <= visibleStart) null else {
            val dur = (visibleEnd - visibleStart) / 60_000
            val endText = if (ends[s.id] == null && dayEnd > System.currentTimeMillis()) "Hiện tại" else fmt(visibleEnd)
            UiVisit("Địa điểm", "${fmt(visibleStart)} → $endText · ${dur / 60}h ${dur % 60}p", s.lat, s.lon)
        }
    }
}
