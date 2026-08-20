package com.family.child

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import kotlin.math.*

class MainActivity : ComponentActivity() {
    private var setupMessage by mutableStateOf<String?>(null)
    private var canOpenPermissionSettings by mutableStateOf(false)
    private var canOpenLocationSettings by mutableStateOf(false)

    private val locationPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        evaluateSetup()
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        evaluateSetup()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ClockScreen(
                setupMessage = setupMessage,
                showPermissionButton = canOpenPermissionSettings,
                showLocationButton = canOpenLocationSettings,
                onPermissionSettings = { openAppSettings() },
                onLocationSettings = { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
            )
        }
        evaluateSetup(requestMissingRuntimePermissions = true)
    }

    override fun onResume() {
        super.onResume()
        evaluateSetup()
    }

    private fun evaluateSetup(requestMissingRuntimePermissions: Boolean = false) {
        canOpenPermissionSettings = false
        canOpenLocationSettings = false

        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine) {
            setupMessage = if (coarse) {
                canOpenPermissionSettings = true
                "Hãy bật Vị trí chính xác để bảo vệ vị trí tốt hơn."
            } else {
                "Ứng dụng cần quyền vị trí để hoạt động."
            }
            if (requestMissingRuntimePermissions && !coarse) {
                locationPermissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }
            return
        }

        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            setupMessage = "Ứng dụng cần quyền thông báo để duy trì chế độ bảo vệ nền."
            if (requestMissingRuntimePermissions) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }

        val locationEnabled = try { getSystemService(LocationManager::class.java).isLocationEnabled } catch (_: Exception) { false }
        if (!locationEnabled) {
            setupMessage = "Vị trí trên điện thoại đang tắt."
            canOpenLocationSettings = true
            return
        }

        try {
            ContextCompat.startForegroundService(this, Intent(this, LocationService::class.java))
        } catch (e: Exception) {
            setupMessage = "Không thể khởi động bảo vệ vị trí: ${e.javaClass.simpleName}"
            return
        }

        if (Build.VERSION.SDK_INT >= 30 && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            setupMessage = "Để tự khôi phục sau khi khởi động lại máy, hãy cho phép vị trí 'Mọi lúc'."
            canOpenPermissionSettings = true
        } else {
            setupMessage = null
        }
    }

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }
}

@Composable
fun ClockScreen(
    setupMessage: String?,
    showPermissionButton: Boolean,
    showLocationButton: Boolean,
    onPermissionSettings: () -> Unit,
    onLocationSettings: () -> Unit
) {
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) { while (true) { now = LocalDateTime.now(); delay(50) } }
    val primary = Color(0xFF54D6FF)

    MaterialTheme(colorScheme = darkColorScheme(primary = primary, background = Color(0xFF05070A), surface = Color(0xFF0B1118))) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                Canvas(Modifier.size(310.dp)) {
                    val c = Offset(size.width / 2, size.height / 2)
                    val r = size.minDimension * .45f
                    drawCircle(Color(0xFF111923), r, c)
                    drawCircle(Color(0xFF2B3A49), r, c, style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
                    for (i in 0 until 60) {
                        val a = Math.toRadians(i * 6.0 - 90)
                        val major = i % 5 == 0
                        val inner = r - (if (major) 18f else 8f)
                        val outer = r - 2f
                        drawLine(
                            if (major) Color(0xFFE8F2FA) else Color(0xFF607080),
                            Offset(c.x + cos(a).toFloat() * inner, c.y + sin(a).toFloat() * inner),
                            Offset(c.x + cos(a).toFloat() * outer, c.y + sin(a).toFloat() * outer),
                            if (major) 3f else 1f
                        )
                    }
                    fun hand(angleDeg: Double, len: Float, width: Float, color: Color) {
                        val a = Math.toRadians(angleDeg - 90)
                        drawLine(color, c, Offset(c.x + cos(a).toFloat() * len, c.y + sin(a).toFloat() * len), width, StrokeCap.Round)
                    }
                    val sec = now.second + now.nano / 1e9
                    val min = now.minute + sec / 60
                    val hour = (now.hour % 12) + min / 60
                    hand(hour * 30, r * .52f, 10f, Color.White)
                    hand(min * 6, r * .72f, 6f, Color.White)
                    hand(sec * 6, r * .82f, 2f, primary)
                    drawCircle(primary, 8f, c)
                }

                Spacer(Modifier.height(28.dp))
                Text("Thời gian quý giá", color = Color(0xFFB8C7D4), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(10.dp))
                Text("Điện thoại của bạn đang được bảo vệ an toàn", color = Color(0xFF7FA7B8), style = MaterialTheme.typography.bodySmall)

                if (setupMessage != null) {
                    Spacer(Modifier.height(24.dp))
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF111923))) {
                        Column(Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(setupMessage, color = Color(0xFFFFC857), style = MaterialTheme.typography.bodySmall)
                            if (showPermissionButton) {
                                TextButton(onClick = onPermissionSettings) { Text("MỞ CÀI ĐẶT QUYỀN") }
                            }
                            if (showLocationButton) {
                                TextButton(onClick = onLocationSettings) { Text("BẬT VỊ TRÍ") }
                            }
                        }
                    }
                }
            }
        }
    }
}
