package com.family.child

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Paint
import android.graphics.Typeface
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import kotlin.math.cos
import kotlin.math.sin

class MainActivity : ComponentActivity() {
    private var setupMessage by mutableStateOf<String?>(null)
    private var canOpenPermissionSettings by mutableStateOf(false)
    private var locationReminderActive by mutableStateOf(false)

    private val locationPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        evaluateSetup()
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        evaluateSetup()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        locationReminderActive = intent?.getBooleanExtra(EXTRA_LOCATION_REMINDER, false) == true
        setContent {
            ClockScreen(
                setupMessage = setupMessage,
                showPermissionButton = canOpenPermissionSettings,
                showLocationReminder = locationReminderActive && !isLocationEnabled(),
                onPermissionSettings = { openAppSettings() },
                onLocationSettings = { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
            )
        }
        evaluateSetup(requestMissingRuntimePermissions = true)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_LOCATION_REMINDER, false)) locationReminderActive = true
        evaluateSetup()
    }

    override fun onResume() {
        super.onResume()
        evaluateSetup()
    }

    private fun evaluateSetup(requestMissingRuntimePermissions: Boolean = false) {
        canOpenPermissionSettings = false

        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine) {
            setupMessage = if (coarse) {
                canOpenPermissionSettings = true
                "Hãy bật Vị trí chính xác để ứng dụng hoạt động ổn định hơn."
            } else {
                "Ứng dụng cần quyền vị trí để hoạt động."
            }
            if (requestMissingRuntimePermissions && !coarse) {
                locationPermissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }
            return
        }

        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            setupMessage = "Ứng dụng cần quyền thông báo để nhận lời nhắn an toàn từ gia đình."
            if (requestMissingRuntimePermissions) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }

        // The foreground service must stay alive even when system Location is off so Parent can
        // receive an accurate diagnostic state. We intentionally do not show an automatic
        // "turn Location on" prompt here; that prompt is shown only after Parent sends a reminder.
        try {
            ContextCompat.startForegroundService(this, Intent(this, LocationService::class.java).putExtra("immediate", true))
        } catch (e: Exception) {
            setupMessage = "Không thể khởi động dịch vụ bảo vệ vị trí: ${e.javaClass.simpleName}"
            return
        }

        if (locationReminderActive && isLocationEnabled()) locationReminderActive = false

        if (Build.VERSION.SDK_INT >= 30 && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            setupMessage = "Để tự khôi phục tốt hơn sau khi khởi động lại máy, hãy cho phép vị trí 'Mọi lúc'."
            canOpenPermissionSettings = true
        } else {
            setupMessage = null
        }
    }

    private fun isLocationEnabled(): Boolean = try {
        getSystemService(LocationManager::class.java).isLocationEnabled
    } catch (_: Exception) {
        false
    }

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    companion object {
        const val EXTRA_LOCATION_REMINDER = "show_location_reminder"
    }
}

@Composable
fun ClockScreen(
    setupMessage: String?,
    showPermissionButton: Boolean,
    showLocationReminder: Boolean,
    onPermissionSettings: () -> Unit,
    onLocationSettings: () -> Unit
) {
    val context = LocalContext.current
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    var quote by remember { mutableStateOf(WisdomStore.next(context)) }
    val interaction = remember { MutableInteractionSource() }

    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            delay(50L)
        }
    }

    val primary = Color(0xFF58D6FF)
    val background = Color(0xFF05080D)
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = primary,
            background = background,
            surface = Color(0xFF0D151F),
            surfaceVariant = Color(0xFF121D29)
        )
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF07111A), background, Color(0xFF030508))
                    )
                )
                .clickable(interactionSource = interaction, indication = null) {
                    quote = WisdomStore.next(context)
                }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 22.dp, vertical = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "THỜI GIAN QUÝ GIÁ",
                    color = Color(0xFF7E9EB0),
                    style = MaterialTheme.typography.labelMedium
                )
                Spacer(Modifier.height(18.dp))

                Canvas(Modifier.size(294.dp)) {
                    val c = Offset(size.width / 2f, size.height / 2f)
                    val r = size.minDimension * .46f
                    drawCircle(Color(0xFF0B141E), r, c)
                    drawCircle(Color(0xFF263746), r, c, style = Stroke(2.2f))
                    drawCircle(Color(0xFF102430), r * .91f, c, style = Stroke(1.5f))
                    drawCircle(Color(0xFF081018), r * .60f, c)

                    for (i in 0 until 60) {
                        val a = Math.toRadians(i * 6.0 - 90.0)
                        val major = i % 5 == 0
                        val inner = r - if (major) 18f else 8f
                        val outer = r - 2f
                        drawLine(
                            color = if (major) Color(0xFFE7F2F8) else Color(0xFF536879),
                            start = Offset(c.x + cos(a).toFloat() * inner, c.y + sin(a).toFloat() * inner),
                            end = Offset(c.x + cos(a).toFloat() * outer, c.y + sin(a).toFloat() * outer),
                            strokeWidth = if (major) 3f else 1f,
                            cap = StrokeCap.Round
                        )
                    }

                    val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = android.graphics.Color.rgb(225, 239, 247)
                        textAlign = Paint.Align.CENTER
                        textSize = r * .13f
                        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    }
                    for (hour in 1..12) {
                        val a = Math.toRadians(hour * 30.0 - 90.0)
                        val nr = r * .73f
                        val x = c.x + cos(a).toFloat() * nr
                        val y = c.y + sin(a).toFloat() * nr - (numberPaint.ascent() + numberPaint.descent()) / 2f
                        drawContext.canvas.nativeCanvas.drawText(hour.toString(), x, y, numberPaint)
                    }

                    fun hand(angleDeg: Double, len: Float, width: Float, color: Color) {
                        val a = Math.toRadians(angleDeg - 90.0)
                        drawLine(
                            color,
                            c,
                            Offset(c.x + cos(a).toFloat() * len, c.y + sin(a).toFloat() * len),
                            width,
                            StrokeCap.Round
                        )
                    }

                    val sec = now.second + now.nano / 1e9
                    val minNow = now.minute + sec / 60.0
                    val hourNow = (now.hour % 12) + minNow / 60.0
                    hand(hourNow * 30.0, r * .47f, 10f, Color.White)
                    hand(minNow * 6.0, r * .66f, 6f, Color.White)
                    hand(sec * 6.0, r * .80f, 2.2f, primary)
                    drawCircle(Color(0xFF061018), 13f, c)
                    drawCircle(primary, 8f, c)
                }

                Spacer(Modifier.height(24.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xCC0C151E)),
                    shape = MaterialTheme.shapes.extraLarge
                ) {
                    Column(
                        Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Crossfade(targetState = quote, animationSpec = tween(320), label = "wisdom") { text ->
                            Text(
                                text,
                                color = Color(0xFFF0F7FA),
                                style = MaterialTheme.typography.titleMedium,
                                textAlign = TextAlign.Center
                            )
                        }
                        Spacer(Modifier.height(9.dp))
                        Text(
                            "Chạm màn hình để xem lời nhắn khác · ${WisdomStore.count()} câu",
                            color = Color(0xFF708A99),
                            style = MaterialTheme.typography.labelSmall,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xAA0A1821)),
                    shape = MaterialTheme.shapes.extraLarge
                ) {
                    Column(
                        Modifier.padding(horizontal = 18.dp, vertical = 15.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Lời nhắn an toàn từ gia đình", color = primary, style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Điện thoại của bạn đang được bảo vệ an toàn.",
                            color = Color(0xFFD8E7EE),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            "Không phát hiện mối đe dọa, lừa đảo.",
                            color = Color(0xFF91AAB7),
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                if (showLocationReminder) {
                    Spacer(Modifier.height(16.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2212)),
                        shape = MaterialTheme.shapes.extraLarge
                    ) {
                        Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Lời nhắc từ gia đình", color = Color(0xFFFFC857), style = MaterialTheme.typography.labelMedium)
                            Spacer(Modifier.height(7.dp))
                            Text(
                                "Hãy bật Vị trí để bảo vệ điện thoại an toàn.",
                                color = Color(0xFFFFE0A1),
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = onLocationSettings) { Text("BẬT VỊ TRÍ") }
                        }
                    }
                }

                if (setupMessage != null) {
                    Spacer(Modifier.height(16.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF111923)),
                        shape = MaterialTheme.shapes.extraLarge
                    ) {
                        Column(Modifier.padding(15.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(setupMessage, color = Color(0xFFFFC857), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                            if (showPermissionButton) {
                                TextButton(onClick = onPermissionSettings) { Text("MỞ CÀI ĐẶT QUYỀN") }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))
            }
        }
    }
}
