package com.family.parent

import android.app.DatePickerDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
import com.google.firebase.firestore.SetOptions
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

data class UiEvent(
    val type: String = "",
    val time: Long = 0,
    val lat: Double? = null,
    val lon: Double? = null,
    val accuracy: Double? = null,
    val id: String = ""
)

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
    override fun onResume() { super.onResume(); mapView?.onResume() }
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
        if (!authReady) {
            FirebaseAuth.getInstance().signInAnonymously()
                .addOnSuccessListener { authReady = true }
                .addOnFailureListener { authError = it.message ?: it.javaClass.simpleName }
        }
    }

    MaterialTheme(colorScheme = darkColorScheme()) {
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

    var lat by remember { mutableStateOf<Double?>(null) }
    var lon by remember { mutableStateOf<Double?>(null) }
    var seen by remember { mutableLongStateOf(0L) }
    var acc by remember { mutableDoubleStateOf(0.0) }
    var deviceStatus by remember { mutableStateOf("unknown") }
    var locationEnabled by remember { mutableStateOf<Boolean?>(null) }
    var lastError by remember { mutableStateOf<String?>(null) }
    var events by remember { mutableStateOf(listOf<UiEvent>()) }
    var firebaseError by remember { mutableStateOf<String?>(null) }
    var refreshState by remember { mutableStateOf("Sẵn sàng cập nhật vị trí") }
    var refreshRequestedAt by remember { mutableLongStateOf(0L) }
    var selectedDayStart by remember { mutableLongStateOf(startOfToday()) }

    DisposableEffect(Unit) {
        val deviceListener = db.collection("devices").document(CHILD_DOC).addSnapshotListener { d, error ->
            if (error != null) {
                firebaseError = "Thiết bị: ${error.code}"
            } else if (d != null && d.exists()) {
                lat = d.getDouble("lastLat")
                lon = d.getDouble("lastLon")
                seen = d.getLong("lastSeen") ?: 0L
                acc = d.getDouble("accuracy") ?: 0.0
                deviceStatus = d.getString("status") ?: "unknown"
                locationEnabled = d.getBoolean("locationEnabled")
                lastError = d.getString("lastError")
                if (refreshRequestedAt > 0 && seen >= refreshRequestedAt) {
                    refreshState = "Đã nhận vị trí mới lúc ${fmt(seen)}"
                    refreshRequestedAt = 0L
                }
            }
        }

        val eventListener = db.collection("devices").document(CHILD_DOC)
            .collection("events")
            .orderBy("time")
            .limitToLast(3000)
            .addSnapshotListener { q, error ->
                if (error != null) firebaseError = "Nhật ký: ${error.code}"
                else events = q?.documents?.mapNotNull { it.toObject(UiEvent::class.java) } ?: emptyList()
            }

        onDispose { deviceListener.remove(); eventListener.remove() }
    }

    LaunchedEffect(refreshRequestedAt) {
        if (refreshRequestedAt > 0) {
            kotlinx.coroutines.delay(30_000L)
            if (refreshRequestedAt > 0) {
                refreshState = "Chưa nhận được vị trí mới sau 30 giây"
            }
        }
    }

    val dayEnd = selectedDayStart + 24L * 60 * 60 * 1000
    val dayEvents = remember(events, selectedDayStart) { events.filter { it.time in selectedDayStart until dayEnd } }
    val visits = remember(dayEvents, selectedDayStart) { toVisits(dayEvents, selectedDayStart, dayEnd) }
    val isToday = selectedDayStart == startOfToday()

    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF55D8FF), background = Color(0xFF070A0F), surface = Color(0xFF111820))) {
        Scaffold(containerColor = MaterialTheme.colorScheme.background) { pad ->
            LazyColumn(
                Modifier.fillMaxSize().padding(pad),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    Text("Guardian", style = MaterialTheme.typography.headlineMedium, color = Color.White)
                    if (seen > 0L) {
                        Text("Cập nhật ${age(seen)} trước · ±${acc.toInt()} m", color = Color(0xFFA8BAC7))
                    } else {
                        Text("Chưa nhận được vị trí từ máy con", color = Color(0xFFFFC857))
                    }
                    if (locationEnabled == false || deviceStatus == "location_off") {
                        Text("Vị trí trên máy con đang tắt hoặc chưa lấy được vị trí mới", color = Color(0xFFFFC857))
                    }
                    if (lastError != null) {
                        Text("Trạng thái máy con: $lastError", color = Color(0xFFFFC857), style = MaterialTheme.typography.bodySmall)
                    }
                    if (firebaseError != null) {
                        Spacer(Modifier.height(6.dp))
                        Text("Lỗi Firebase: $firebaseError", color = MaterialTheme.colorScheme.error)
                        Text("Nếu thấy PERMISSION_DENIED thì Firestore Rules đang chặn app.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }

                item { Card(Modifier.fillMaxWidth().height(360.dp)) { OsmMap(lat, lon, dayEvents, savedState, onMapViewCreated) } }
                item { Text("Bản đồ: © OpenStreetMap contributors", color = Color(0xFF8799A6), style = MaterialTheme.typography.labelSmall) }

                item {
                    Button(
                        onClick = {
                            firebaseError = null
                            refreshState = "Đang gửi yêu cầu cập nhật..."
                            val requestAt = System.currentTimeMillis()
                            db.collection("devices").document(CHILD_DOC)
                                .set(mapOf("refreshRequestedAt" to requestAt), SetOptions.merge())
                                .addOnSuccessListener {
                                    refreshRequestedAt = requestAt
                                    refreshState = "Đã gửi yêu cầu · đang chờ máy con"
                                }
                                .addOnFailureListener {
                                    refreshRequestedAt = 0L
                                    refreshState = "Gửi yêu cầu thất bại: ${it.javaClass.simpleName}"
                                    firebaseError = "Yêu cầu cập nhật: ${it.javaClass.simpleName}"
                                }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("CẬP NHẬT VỊ TRÍ NGAY") }
                }

                item { Text(refreshState, color = Color(0xFFA8BAC7), style = MaterialTheme.typography.bodySmall) }

                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = { if (lat != null && lon != null) openGoogleMapsPoint(context, lat!!, lon!!) },
                            enabled = lat != null && lon != null,
                            modifier = Modifier.weight(1f)
                        ) { Text("XEM TRÊN GOOGLE MAPS") }
                        OutlinedButton(
                            onClick = { if (lat != null && lon != null) openGoogleMapsDirections(context, lat!!, lon!!) },
                            enabled = lat != null && lon != null,
                            modifier = Modifier.weight(1f)
                        ) { Text("DẪN ĐƯỜNG") }
                    }
                    if (lat == null || lon == null) {
                        Spacer(Modifier.height(6.dp))
                        Text("Hai nút trên sẽ hoạt động ngay khi nhận được tọa độ thật từ máy con.", color = Color(0xFF8799A6), style = MaterialTheme.typography.bodySmall)
                    }
                }

                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(if (isToday) "Nhật ký hôm nay" else "Nhật ký ${formatDate(selectedDayStart)}", style = MaterialTheme.typography.titleLarge, color = Color.White)
                        OutlinedButton(onClick = {
                            val c = Calendar.getInstance().apply { timeInMillis = selectedDayStart }
                            DatePickerDialog(
                                context,
                                { _, y, m, d -> selectedDayStart = startOfDate(y, m, d) },
                                c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)
                            ).show()
                        }) { Text("CHỌN NGÀY") }
                    }
                    if (!isToday) {
                        TextButton(onClick = { selectedDayStart = startOfToday() }) { Text("Quay về hôm nay") }
                    }
                }

                if (dayEvents.isEmpty()) {
                    item { Text("Ngày này chưa có dữ liệu vị trí.", color = Color(0xFFA8BAC7)) }
                } else if (visits.isNotEmpty()) {
                    items(visits) { visit ->
                        Card {
                            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                Text(visit.title, color = Color.White, style = MaterialTheme.typography.titleMedium)
                                Text(visit.detail, color = Color(0xFFB7C8D5))
                                if (visit.lat != null && visit.lon != null) {
                                    Spacer(Modifier.height(8.dp))
                                    TextButton(onClick = { openGoogleMapsPoint(context, visit.lat, visit.lon) }, contentPadding = PaddingValues(0.dp)) {
                                        Text("XEM ĐIỂM NÀY TRÊN GOOGLE MAPS")
                                    }
                                }
                            }
                        }
                    }
                } else {
                    items(dayEvents.filter { it.type == "location_sample" }.sortedByDescending { it.time }.take(30)) { sample ->
                        Card {
                            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                Text("Điểm ghi nhận ${fmt(sample.time)}", color = Color.White)
                                Text("${sample.lat ?: 0.0}, ${sample.lon ?: 0.0}${sample.accuracy?.let { " · ±${it.toInt()} m" } ?: ""}", color = Color(0xFFB7C8D5))
                                if (sample.lat != null && sample.lon != null) {
                                    TextButton(onClick = { openGoogleMapsPoint(context, sample.lat, sample.lon) }, contentPadding = PaddingValues(0.dp)) {
                                        Text("XEM TRÊN GOOGLE MAPS")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OsmMap(lat: Double?, lon: Double?, events: List<UiEvent>, savedState: Bundle?, onMapViewCreated: (MapView) -> Unit) {
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
                        showMapData(readyMap, lat, lon, events)
                        renderedKey = mapKey(lat, lon, events)
                    }
                }
            }
        },
        update = {
            val current = map
            val key = mapKey(lat, lon, events)
            if (current != null && key != renderedKey) {
                showMapData(current, lat, lon, events)
                renderedKey = key
            }
        }
    )
}

private fun mapKey(lat: Double?, lon: Double?, events: List<UiEvent>): Int = listOf(lat, lon, events.hashCode()).hashCode()

@Suppress("DEPRECATION")
private fun showMapData(map: MapLibreMap, lat: Double?, lon: Double?, events: List<UiEvent>) {
    map.clear()
    val points = events
        .filter { (it.type == "location_sample" || it.type == "trip_point") && it.lat != null && it.lon != null }
        .sortedBy { it.time }
        .map { LatLng(it.lat!!, it.lon!!) }

    if (points.size >= 2) map.addPolyline(PolylineOptions().addAll(points).width(5f))

    events.filter { it.type == "visit_start" && it.lat != null && it.lon != null }
        .forEachIndexed { index, e ->
            map.addMarker(MarkerOptions().position(LatLng(e.lat!!, e.lon!!)).title("Điểm dừng ${index + 1}").snippet(fmt(e.time)))
        }

    when {
        lat != null && lon != null -> {
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
private fun formatDate(t: Long): String = SimpleDateFormat("dd/MM/yyyy", Locale("vi", "VN")).format(Date(t))

private fun startOfToday(): Long {
    val c = Calendar.getInstance()
    c.set(Calendar.HOUR_OF_DAY, 0)
    c.set(Calendar.MINUTE, 0)
    c.set(Calendar.SECOND, 0)
    c.set(Calendar.MILLISECOND, 0)
    return c.timeInMillis
}

private fun startOfDate(year: Int, month: Int, day: Int): Long {
    val c = Calendar.getInstance()
    c.set(Calendar.YEAR, year)
    c.set(Calendar.MONTH, month)
    c.set(Calendar.DAY_OF_MONTH, day)
    c.set(Calendar.HOUR_OF_DAY, 0)
    c.set(Calendar.MINUTE, 0)
    c.set(Calendar.SECOND, 0)
    c.set(Calendar.MILLISECOND, 0)
    return c.timeInMillis
}

fun toVisits(events: List<UiEvent>, dayStart: Long, dayEnd: Long): List<UiVisit> {
    val starts = events.filter { it.type == "visit_start" }.associateBy { it.id }
    val ends = events.filter { it.type == "visit_end" }.associateBy { it.id }
    val now = System.currentTimeMillis()

    return starts.values.sortedByDescending { it.time }.mapNotNull { s ->
        val rawEnd = ends[s.id]?.time ?: minOf(dayEnd, now)
        val visibleStart = maxOf(s.time, dayStart)
        val visibleEnd = minOf(rawEnd, dayEnd, now)
        if (visibleEnd <= visibleStart) null
        else {
            val dur = (visibleEnd - visibleStart) / 60_000
            UiVisit(
                "Địa điểm",
                "${fmt(visibleStart)} → ${if (ends[s.id] == null && dayEnd > now) "Hiện tại" else fmt(visibleEnd)} · ${dur / 60}h ${dur % 60}p",
                s.lat,
                s.lon
            )
        }
    }
}
