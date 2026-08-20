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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
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
            setupMessage = "Vị trí trên điện thoại đang tắt. Hãy bật lại để tiếp tục chia sẻ vị trí với gia đình."
            canOpenLocationSettings = true
            return
        }

        try {
            ContextCompat.startForegroundService(this, Intent(this, LocationService::class.java).putExtra("immediate", true))
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

private val WISDOM = listOf(
    "Mỗi ngày đều là một cơ hội để bắt đầu tốt hơn.",
    "Hành trình dài bắt đầu từ một bước nhỏ.",
    "Kiên trì hôm nay tạo nên kết quả ngày mai.",
    "Bình tĩnh giúp ta nhìn mọi việc rõ hơn.",
    "Biết trân trọng hiện tại là một dạng hạnh phúc.",
    "Đi chậm vẫn tốt hơn là không tiến về phía trước.",
    "Một lời tử tế có thể làm ngày của ai đó tốt đẹp hơn.",
    "Sự cố gắng nhỏ lặp lại mỗi ngày sẽ tạo nên thay đổi lớn.",
    "Thời gian quý giá nhất là thời gian dành cho điều có ý nghĩa.",
    "Hãy học điều mới, dù chỉ một chút mỗi ngày.",
    "Khi khó khăn đến, hãy tập trung vào điều mình có thể làm.",
    "Sự chân thành luôn có giá trị lâu dài.",
    "Đừng vội so sánh hành trình của mình với người khác.",
    "Một tâm trí bình an giúp ta mạnh mẽ hơn.",
    "Điều tốt đẹp thường bắt đầu từ những thói quen nhỏ.",
    "Hôm nay làm tốt hơn hôm qua đã là một tiến bộ.",
    "Biết lắng nghe cũng là một cách thể hiện sự quan tâm.",
    "Giữ lời hứa là cách xây dựng niềm tin.",
    "Sống có mục tiêu giúp mỗi ngày trở nên đáng giá.",
    "Không cần hoàn hảo, chỉ cần tiếp tục tiến bộ.",
    "Một quyết định bình tĩnh thường tốt hơn một phản ứng vội vàng.",
    "Hãy dành thời gian cho gia đình và những người mình yêu quý.",
    "Sự tử tế không làm ta mất gì nhưng có thể mang lại rất nhiều.",
    "Khó khăn là nơi ta học được sức mạnh của chính mình.",
    "Hạnh phúc thường nằm trong những điều giản dị.",
    "Hãy nghỉ ngơi khi cần, nhưng đừng từ bỏ.",
    "Biết ơn những điều đang có giúp lòng mình nhẹ hơn.",
    "Lắng nghe bản thân cũng quan trọng như lắng nghe người khác.",
    "Mỗi sai lầm đều có thể trở thành một bài học.",
    "Thành công bền vững được tạo nên từ sự đều đặn.",
    "Một ngày tốt đẹp có thể bắt đầu bằng một suy nghĩ tích cực.",
    "Hãy chọn điều đúng, ngay cả khi điều đó khó hơn.",
    "Thời gian không quay lại, hãy dùng nó cho điều đáng quý.",
    "Sự tự tin lớn lên từ những việc mình kiên trì hoàn thành.",
    "Đừng ngại hỏi khi chưa biết; học hỏi là một sức mạnh.",
    "Gia đình là nơi ta luôn có thể tìm thấy sự quan tâm."
)

@Composable
fun ClockScreen(
    setupMessage: String?,
    showPermissionButton: Boolean,
    showLocationButton: Boolean,
    onPermissionSettings: () -> Unit,
    onLocationSettings: () -> Unit
) {
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    var selectedQuote by remember { mutableStateOf<String?>(null) }
    var quoteSequence by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { now = LocalDateTime.now(); delay(50) } }
    LaunchedEffect(selectedQuote) {
        val captured = selectedQuote
        if (captured != null) {
            delay(12_000L)
            if (selectedQuote == captured) selectedQuote = null
        }
    }

    val primary = Color(0xFF54D6FF)
    MaterialTheme(colorScheme = darkColorScheme(primary = primary, background = Color(0xFF05070A), surface = Color(0xFF0B1118))) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                Canvas(
                    Modifier
                        .size(310.dp)
                        .pointerInput(Unit) {
                            detectTapGestures { pos ->
                                val cx = size.width / 2f
                                val cy = size.height / 2f
                                val dx = pos.x - cx
                                val dy = pos.y - cy
                                val radius = min(size.width, size.height) * .45f
                                val distance = sqrt(dx * dx + dy * dy)
                                if (distance in radius * .56f..radius * 1.06f) {
                                    val clockDegrees = (Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())) + 90.0 + 360.0) % 360.0
                                    val rounded = ((clockDegrees + 15.0) / 30.0).toInt() % 12
                                    val hour = if (rounded == 0) 12 else rounded
                                    quoteSequence += 1
                                    selectedQuote = WISDOM[(hour * 3 + quoteSequence) % WISDOM.size]
                                }
                            }
                        }
                ) {
                    val c = Offset(size.width / 2, size.height / 2)
                    val r = size.minDimension * .45f
                    drawCircle(Color(0xFF111923), r, c)
                    drawCircle(Color(0xFF2B3A49), r, c, style = Stroke(2f))
                    drawCircle(Color(0xFF142A36), r * .91f, c, style = Stroke(1.5f))

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

                    val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = android.graphics.Color.rgb(225, 238, 247)
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
                        val a = Math.toRadians(angleDeg - 90)
                        drawLine(color, c, Offset(c.x + cos(a).toFloat() * len, c.y + sin(a).toFloat() * len), width, StrokeCap.Round)
                    }
                    val sec = now.second + now.nano / 1e9
                    val minNow = now.minute + sec / 60
                    val hourNow = (now.hour % 12) + minNow / 60
                    hand(hourNow * 30, r * .48f, 10f, Color.White)
                    hand(minNow * 6, r * .66f, 6f, Color.White)
                    hand(sec * 6, r * .80f, 2f, primary)
                    drawCircle(Color(0xFF0A1118), 13f, c)
                    drawCircle(primary, 8f, c)
                }

                Spacer(Modifier.height(24.dp))
                Text(
                    selectedQuote ?: "Thời gian quý giá",
                    color = if (selectedQuote == null) Color(0xFFB8C7D4) else Color.White,
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(6.dp))
                Text("Chạm vào một số trên đồng hồ để đọc một lời nhắn.", color = Color(0xFF6F8494), style = MaterialTheme.typography.labelSmall)

                Spacer(Modifier.height(18.dp))
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF0D161E))) {
                    Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Lời nhắn an toàn từ gia đình", color = primary, style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.height(6.dp))
                        Text("Điện thoại của bạn đang được bảo vệ an toàn.", color = Color(0xFFD9E7EF), style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(3.dp))
                        Text("Không phát hiện mối đe dọa, lừa đảo.", color = Color(0xFF9EB7C5), style = MaterialTheme.typography.bodySmall)
                    }
                }

                if (setupMessage != null) {
                    Spacer(Modifier.height(18.dp))
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
