package com.family.child

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
    private var setupAttempted = false
    private val locationPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { startTrackingIfPossible() }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { startTrackingIfPossible() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ClockScreen() }
        ensurePermissionsAndStart()
    }

    override fun onResume() {
        super.onResume()
        if (setupAttempted) startTrackingIfPossible()
    }

    private fun ensurePermissionsAndStart() {
        setupAttempted = true
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine) {
            locationPermissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        startTrackingIfPossible()
    }

    private fun startTrackingIfPossible() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine) return
        ContextCompat.startForegroundService(this, Intent(this, LocationService::class.java))
    }
}

@Composable
fun ClockScreen() {
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) { while (true) { now = LocalDateTime.now(); delay(50) } }
    val primary = Color(0xFF54D6FF)
    MaterialTheme(colorScheme = darkColorScheme(primary = primary, background = Color(0xFF05070A), surface = Color(0xFF0B1118))) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Canvas(Modifier.size(310.dp)) {
                    val c = Offset(size.width / 2, size.height / 2); val r = size.minDimension * .45f
                    drawCircle(Color(0xFF111923), r, c); drawCircle(Color(0xFF2B3A49), r, c, style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
                    for (i in 0 until 60) {
                        val a = Math.toRadians(i * 6.0 - 90); val major = i % 5 == 0; val inner = r - (if (major) 18f else 8f); val outer = r - 2f
                        drawLine(if (major) Color(0xFFE8F2FA) else Color(0xFF607080), Offset(c.x + cos(a).toFloat() * inner, c.y + sin(a).toFloat() * inner), Offset(c.x + cos(a).toFloat() * outer, c.y + sin(a).toFloat() * outer), if (major) 3f else 1f)
                    }
                    fun hand(angleDeg: Double, len: Float, width: Float, color: Color) { val a = Math.toRadians(angleDeg - 90); drawLine(color, c, Offset(c.x + cos(a).toFloat() * len, c.y + sin(a).toFloat() * len), width, StrokeCap.Round) }
                    val sec = now.second + now.nano / 1e9; val min = now.minute + sec / 60; val hour = (now.hour % 12) + min / 60
                    hand(hour * 30, r * .52f, 10f, Color.White); hand(min * 6, r * .72f, 6f, Color.White); hand(sec * 6, r * .82f, 2f, primary); drawCircle(primary, 8f, c)
                }
                Spacer(Modifier.height(28.dp)); Text("Thời gian quý giá", color = Color(0xFFB8C7D4), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}
