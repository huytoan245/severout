package com.family.parent

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
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

private const val CHILD_DOC = "child-01"
private const val OSM_STYLE = "asset://osm_raster_style.json"
private const val DAY_MS = 86_400_000L

data class UiEvent(
    val type: String = "",
    val time: Long = 0,
    val lat: Double? = null,
    val lon: Double? = null,
    val id: String = ""
)

data class LatestLocation(
    val lat: Double,
    val lon: Double,
    val accuracy: Double,
    val lastSeen: Long
)

data class TimelineItem(
    val title: String,
    val detail: String,
    val lat: Double?,
    val lon: Double?,
    val start: Long
)

class MainActivity : ComponentActivity() {
    private var mapView: MapView? = null
    private var restoredState: Bundle? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restoredState = savedInstanceState
        MapLibre.getInstance(this)
        setContent {
            ParentApp(
                savedState = restoredState,
                onMapViewCreated = { mapView = it }
            )
        }
    }

    override fun onStart() {
        super.onStart()
        mapView?.onStart()
    }

    override fun onResume() {
        super.onResume()
        mapView?.onResume()
    }

    override fun onPause() {
        mapView?.onPause()
        super.onPause()
    }

    override fun onStop() {
        mapView?.onStop()
        super.onStop()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView?.onLowMemory()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        mapView?.onSaveInstanceState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        mapView?.onDestroy()
        mapView = null
        super.onDestroy()
    }
}

@Composable
fun ParentApp(savedState: Bundle?, onMapViewCreated: (MapView) -> Unit) {
    var authReady by remember { mutableStateOf(FirebaseAuth.getInstance().currentUser != null) }
    var authError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        if (!authReady) {
            FirebaseAuth.getInstance().signInAnonymously()
                .addOnSuccessListener { authReady = true }
                .addOnFailureListener { authError = it.localizedMessage ?: "Không đăng nhập được Firebase" }
        }
    }

    if (!authReady) {
        MaterialTheme(colorScheme = darkColorScheme()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (authError == null) {
                    CircularProgressIndicator()
                } else {
                    Text("Lỗi Firebase: $authError", color = Color(0xFFFF8A80))
                }
            }
        }
        return
    }
    ParentDashboard(savedState, onMapViewCreated)
}

@Composable
fun ParentDashboard(savedState: Bundle?, onMapViewCreated: (MapView) -> Unit) {
    val context = LocalContext.current
    val db = remember { FirebaseFirestore.getInstance() }
    var latest by remember { mutableStateOf<LatestLocation?>(null) }
    var heartbeatAt by remember { mutableLongStateOf(0L) }
    var locationEnabled by remember { mutableStateOf<Boolean?>(null) }
    var backgroundGranted by remember { mutableStateOf<Boolean?>(null) }
    var serviceRunning by remember { mutableStateOf<Boolean?>(null) }
    var firebaseError by remember { mutableStateOf<String?>(null) }
    var events by remember { mutableStateOf(listOf<UiEvent>()) }
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }
    var refreshRequestedAt by remember { mutableLongStateOf(0L) }
    var refreshMessage by remember { mutableStateOf<String?>(null) }

    val today = LocalDate.now()
    val (dayStart, dayEnd) = remember(selectedDate) { dayBounds(selectedDate) }

    DisposableEffect(Unit) {
        val registration = db.collection("devices").document(CHILD_DOC)
            .addSnapshotListener { document, error ->
                if (error != null) {
                    firebaseError = "${error.code}: ${error.localizedMessage ?: "Không đọc được dữ liệu"}"
                    return@addSnapshotListener
                }
                firebaseError = null
                if (document != null && document.exists()) {
                    val lat = document.getDouble("lastLat")
                    val lon = document.getDouble("lastLon")
                    val seen = document.getLong("lastSeen") ?: 0L
                    val accuracy = document.getDouble("accuracy") ?: 0.0
                    if (lat != null && lon != null && seen > 0L) {
                        latest = LatestLocation(lat, lon, accuracy, seen)
                        if (refreshRequestedAt > 0L && seen >= refreshRequestedAt) {
                            refreshMessage = "Đã nhận vị trí mới"
                            refreshRequestedAt = 0L
                        }
                    }
                    heartbeatAt = document.getLong("heartbeatAt") ?: 0L
                    locationEnabled = document.getBoolean("locationEnabled")
                    backgroundGranted = document.getBoolean("backgroundLocationGranted")
                    serviceRunning = document.getBoolean("serviceRunning")
                }
            }
        onDispose { registration.remove() }
    }

    DisposableEffect(selectedDate) {
        val queryStart = dayStart - DAY_MS
        val registration = db.collection("devices").document(CHILD_DOC)
            .collection("events")
            .whereGreaterThanOrEqualTo("time", queryStart)
            .whereLessThan("time", dayEnd)
            .orderBy("time")
            .limit(1000)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    firebaseError = "${error.code}: ${error.localizedMessage ?: "Không đọc được nhật ký"}"
                    return@addSnapshotListener
                }
                events = snapshot?.documents?.mapNotNull { it.toObject(UiEvent::class.java) } ?: emptyList()
            }
        onDispose { registration.remove() }
    }

    val timeline = remember(events, dayStart, dayEnd) { toTimeline(events, dayStart, dayEnd) }
    val eventsForDay = remember(events, dayStart, dayEnd) { events.filter { it.time in dayStart until dayEnd } }
    val currentLatest = if (selectedDate == today) latest else null

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF55D8FF),
            background = Color(0xFF070A0F),
            surface = Color(0xFF111820)
        )
    ) {
        Scaffold(containerColor = MaterialTheme.colorScheme.background) { pad ->
            LazyColumn(
                Modifier.fillMaxSize().padding(pad),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    Text("Guardian", style = MaterialTheme.typography.headlineMedium, color = Color.White)
                    Text(statusText(latest, heartbeatAt, locationEnabled, serviceRunning), color = Color(0xFFA8BAC7))
                    if (backgroundGranted == false) {
                        Text(
                            "Máy con chưa cấp quyền vị trí nền; theo dõi sau khởi động lại có thể bị gián đoạn.",
                            color = Color(0xFFFFCC80),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    firebaseError?.let {
                        Spacer(Modifier.height(6.dp))
                        Text("Lỗi Firebase: $it", color = Color(0xFFFF8A80), style = MaterialTheme.typography.bodySmall)
                    }
                    refreshMessage?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, color = Color(0xFF7FE6B8), style = MaterialTheme.typography.bodySmall)
                    }
                }

                item {
                    Card(Modifier.fillMaxWidth().height(360.dp)) {
                        OsmMap(
                            latest = currentLatest,
                            events = eventsForDay,
                            savedState = savedState,
                            onMapViewCreated = onMapViewCreated
                        )
                    }
                }

                item {
                    Text(
                        "Bản đồ: © OpenStreetMap contributors",
                        color = Color(0xFF8799A6),
                        style = MaterialTheme.typography.labelSmall
                    )
                }

                item {
                    Button(
                        onClick = {
                            val requested = System.currentTimeMillis()
                            refreshRequestedAt = requested
                            refreshMessage = "Đang yêu cầu máy con cập nhật vị trí…"
                            db.collection("devices").document(CHILD_DOC)
                                .set(mapOf("refreshRequestedAt" to requested), SetOptions.merge())
                                .addOnFailureListener {
                                    refreshMessage = "Không gửi được yêu cầu: ${it.localizedMessage ?: "lỗi Firebase"}"
                                }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("CẬP NHẬT VỊ TRÍ NGAY")
                    }
                }

                item {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = { latest?.let { openGoogleMapsPoint(context, it.lat, it.lon) } },
                            enabled = latest != null,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("XEM TRÊN GOOGLE MAPS")
                        }
                        OutlinedButton(
                            onClick = { latest?.let { openGoogleMapsDirections(context, it.lat, it.lon) } },
                            enabled = latest != null,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("DẪN ĐƯỜNG")
                        }
                    }
                }

                item {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { selectedDate = selectedDate.minusDays(1) }) {
                            Text("‹ Ngày trước")
                        }
                        Text(dayTitle(selectedDate), style = MaterialTheme.typography.titleMedium, color = Color.White)
                        TextButton(
                            onClick = { selectedDate = selectedDate.plusDays(1) },
                            enabled = selectedDate < today
                        ) {
                            Text("Ngày sau ›")
                        }
                    }
                }

                item {
                    Text(
                        if (selectedDate == today) "Nhật ký hôm nay" else "Nhật ký ${dayTitle(selectedDate)}",
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White
                    )
                }

                if (timeline.isEmpty()) {
                    item {
                        Text(
                            if (firebaseError != null) "Chưa thể tải nhật ký do lỗi kết nối Firebase."
                            else "Ngày này chưa có dữ liệu vị trí.",
                            color = Color(0xFF8799A6)
                        )
                    }
                } else {
                    items(timeline) { item ->
                        Card {
                            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                Text(item.title, color = Color.White, style = MaterialTheme.typography.titleMedium)
                                Text(item.detail, color = Color(0xFFB7C8D5))
                                if (item.lat != null && item.lon != null) {
                                    Spacer(Modifier.height(8.dp))
                                    TextButton(
                                        onClick = { openGoogleMapsPoint(context, item.lat, item.lon) },
                                        contentPadding = PaddingValues(0.dp)
                                    ) {
                                        Text("XEM ĐIỂM NÀY TRÊN GOOGLE MAPS")
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
private fun OsmMap(
    latest: LatestLocation?,
    events: List<UiEvent>,
    savedState: Bundle?,
    onMapViewCreated: (MapView) -> Unit
) {
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var renderedKey by remember { mutableIntStateOf(Int.MIN_VALUE) }
    val renderKey = 31 * (latest?.hashCode() ?: 0) + events.hashCode()

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            MapView(context).also { view ->
                view.onCreate(savedState)
                onMapViewCreated(view)
                view.getMapAsync { readyMap ->
                    map = readyMap
                    readyMap.setStyle(Style.Builder().fromUri(OSM_STYLE)) {
                        showMapData(readyMap, latest, events)
                        renderedKey = renderKey
                    }
                }
            }
        },
        update = {
            val currentMap = map
            if (currentMap != null && renderKey != renderedKey) {
                showMapData(currentMap, latest, events)
                renderedKey = renderKey
            }
        }
    )
}

@Suppress("DEPRECATION")
private fun showMapData(map: MapLibreMap, latest: LatestLocation?, events: List<UiEvent>) {
    map.clear()

    events.filter { it.type == "visit_start" && it.lat != null && it.lon != null }
        .forEachIndexed { index, event ->
            map.addMarker(
                MarkerOptions()
                    .position(LatLng(event.lat!!, event.lon!!))
                    .title("Điểm dừng ${index + 1}")
                    .snippet(fmt(event.time))
            )
        }

    val route = events
        .filter { it.type in setOf("trip_start", "trip_point", "trip_end") && it.lat != null && it.lon != null }
        .map { LatLng(it.lat!!, it.lon!!) }
    if (route.size >= 2) {
        map.addPolyline(PolylineOptions().addAll(route).width(5f))
    }

    latest?.let {
        map.addMarker(
            MarkerOptions()
                .position(LatLng(it.lat, it.lon))
                .title("Vị trí mới nhất")
                .snippet("±${it.accuracy.toInt()} m · ${fmt(it.lastSeen)}")
        )
    }

    val target = latest?.let { LatLng(it.lat, it.lon) }
        ?: events.asReversed().firstOrNull { it.lat != null && it.lon != null }?.let { LatLng(it.lat!!, it.lon!!) }
        ?: LatLng(16.0, 106.0)
    val zoom = if (latest != null || events.any { it.lat != null && it.lon != null }) 16.0 else 5.0
    map.cameraPosition = CameraPosition.Builder().target(target).zoom(zoom).build()
}

private fun statusText(
    latest: LatestLocation?,
    heartbeatAt: Long,
    locationEnabled: Boolean?,
    serviceRunning: Boolean?
): String {
    val now = System.currentTimeMillis()
    val heartbeatFresh = heartbeatAt > 0L && now - heartbeatAt < 10 * 60_000L
    if (heartbeatFresh && locationEnabled == false) {
        return latest?.let { "Máy con đang tắt Vị trí · vị trí cuối ${age(it.lastSeen)} trước" }
            ?: "Máy con đang online nhưng đang tắt Vị trí"
    }
    if (heartbeatFresh && serviceRunning == true && latest == null) {
        return "Máy con đang online nhưng chưa có vị trí hợp lệ"
    }
    if (latest == null) return "Chưa nhận được vị trí từ máy con"
    val stale = now - latest.lastSeen > 10 * 60_000L
    return if (stale) {
        "Vị trí cuối ${age(latest.lastSeen)} trước · ±${latest.accuracy.toInt()} m"
    } else {
        "Cập nhật ${age(latest.lastSeen)} trước · ±${latest.accuracy.toInt()} m"
    }
}

private fun dayBounds(date: LocalDate): Pair<Long, Long> {
    val zone = ZoneId.systemDefault()
    val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
    val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    return start to end
}

private fun dayTitle(date: LocalDate): String =
    if (date == LocalDate.now()) "Hôm nay" else date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))

private fun toTimeline(events: List<UiEvent>, dayStart: Long, dayEnd: Long): List<TimelineItem> {
    val now = System.currentTimeMillis()
    val visitStarts = events.filter { it.type == "visit_start" }.associateBy { it.id }
    val visitEnds = events.filter { it.type == "visit_end" }.associateBy { it.id }
    val tripStarts = events.filter { it.type == "trip_start" }.associateBy { it.id }
    val tripEnds = events.filter { it.type == "trip_end" }.associateBy { it.id }
    val result = mutableListOf<TimelineItem>()

    visitStarts.values.forEach { start ->
        val endEvent = visitEnds[start.id]
        val actualEnd = endEvent?.time ?: now
        if (start.time < dayEnd && actualEnd >= dayStart) {
            val shownStart = maxOf(start.time, dayStart)
            val shownEnd = minOf(actualEnd, dayEnd, now)
            result += TimelineItem(
                title = "Địa điểm",
                detail = "${fmt(shownStart)} → ${if (endEvent == null && dayEnd > now) "Hiện tại" else fmt(shownEnd)} · ${durationText(shownEnd - shownStart)}",
                lat = start.lat,
                lon = start.lon,
                start = shownStart
            )
        }
    }

    tripStarts.values.forEach { start ->
        val endEvent = tripEnds[start.id]
        val actualEnd = endEvent?.time ?: now
        if (start.time < dayEnd && actualEnd >= dayStart) {
            val shownStart = maxOf(start.time, dayStart)
            val shownEnd = minOf(actualEnd, dayEnd, now)
            result += TimelineItem(
                title = "Di chuyển",
                detail = "${fmt(shownStart)} → ${if (endEvent == null && dayEnd > now) "Hiện tại" else fmt(shownEnd)} · ${durationText(shownEnd - shownStart)}",
                lat = start.lat,
                lon = start.lon,
                start = shownStart
            )
        }
    }

    return result.sortedByDescending { it.start }
}

private fun durationText(ms: Long): String {
    val minutes = ms.coerceAtLeast(0L) / 60_000L
    return "${minutes / 60}h ${minutes % 60}p"
}

private fun openGoogleMapsPoint(context: android.content.Context, lat: Double, lon: Double) {
    val uri = Uri.parse("https://www.google.com/maps/search/?api=1&query=$lat,$lon")
    val appIntent = Intent(Intent.ACTION_VIEW, uri).apply { setPackage("com.google.android.apps.maps") }
    try {
        context.startActivity(appIntent)
    } catch (_: ActivityNotFoundException) {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    }
}

private fun openGoogleMapsDirections(context: android.content.Context, lat: Double, lon: Double) {
    val uri = Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$lat,$lon")
    val appIntent = Intent(Intent.ACTION_VIEW, uri).apply { setPackage("com.google.android.apps.maps") }
    try {
        context.startActivity(appIntent)
    } catch (_: ActivityNotFoundException) {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    }
}

fun age(t: Long): String {
    val s = ((System.currentTimeMillis() - t) / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60} phút"
        s < 86_400 -> "${s / 3600} giờ"
        else -> "${s / 86_400} ngày"
    }
}

fun fmt(t: Long): String = SimpleDateFormat("HH:mm", Locale("vi", "VN")).format(Date(t))
