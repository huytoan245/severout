package com.family.child

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps device Wi-Fi connected but can bind only the Child app process to a
 * validated Cellular path when Wi-Fi cannot reach Firebase. Healthy probing is
 * intentionally slower than degraded probing to reduce long-running battery use.
 */
class NetworkFailoverManager(
    context: Context,
    private val onRouteChanged: (String) -> Unit
) {
    data class Snapshot(
        val routeMode: String,
        val cellularFailoverActive: Boolean,
        val cellularFailoverSince: Long,
        val wifiServerProbeOkAt: Long,
        val wifiServerProbeFailAt: Long,
        val wifiServerFailureCount: Int,
        val cellularAvailable: Boolean
    )

    private val appContext = context.applicationContext
    private val cm = appContext.getSystemService(ConnectivityManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val stopped = AtomicBoolean(false)

    @Volatile private var failoverActive = false
    @Volatile private var failoverSince = 0L
    @Volatile private var wifiProbeOkAt = 0L
    @Volatile private var wifiProbeFailAt = 0L
    @Volatile private var wifiFailures = 0
    @Volatile private var cellularAvailable = false
    @Volatile private var currentMode = "unknown"

    private var cellularNetwork: Network? = null
    private var cellularCallback: ConnectivityManager.NetworkCallback? = null
    private var requestTimeout: Runnable? = null
    private var wifiRecoverySuccesses = 0

    private val probeLoop = object : Runnable {
        override fun run() {
            if (stopped.get()) return
            evaluate()
            main.postDelayed(this, nextProbeDelay())
        }
    }

    fun start() {
        stopped.set(false)
        main.removeCallbacks(probeLoop)
        main.postDelayed(probeLoop, INITIAL_PROBE_DELAY_MS)
    }

    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        main.removeCallbacks(probeLoop)
        requestTimeout?.let { main.removeCallbacks(it) }
        requestTimeout = null
        try { cm.bindProcessToNetwork(null) } catch (_: Exception) {}
        failoverActive = false
        failoverSince = 0L
        cleanupCellularCallback()
        cellularNetwork = null
        cellularAvailable = false
        io.shutdownNow()
    }

    fun snapshot(): Snapshot = Snapshot(
        routeMode = currentRouteMode(),
        cellularFailoverActive = failoverActive,
        cellularFailoverSince = failoverSince,
        wifiServerProbeOkAt = wifiProbeOkAt,
        wifiServerProbeFailAt = wifiProbeFailAt,
        wifiServerFailureCount = wifiFailures,
        cellularAvailable = cellularAvailable
    )

    fun probeNow() {
        if (stopped.get()) return
        main.removeCallbacks(probeLoop)
        main.post(probeLoop)
    }

    private fun nextProbeDelay(): Long = when {
        failoverActive -> DEGRADED_PROBE_INTERVAL_MS
        currentMode == "wifi_server_unreachable" || currentMode == "requesting_cellular" -> DEGRADED_PROBE_INTERVAL_MS
        currentMode == "wifi" -> HEALTHY_PROBE_INTERVAL_MS
        else -> DEFAULT_PROBE_INTERVAL_MS
    }

    private fun evaluate() {
        if (hasActiveVpn()) {
            wifiFailures = 0
            wifiRecoverySuccesses = 0
            updateMode("vpn")
            return
        }

        val wifi = findNetwork(NetworkCapabilities.TRANSPORT_WIFI)
        if (failoverActive) {
            if (wifi == null) {
                wifiRecoverySuccesses = 0
                updateMode("cellular_failover")
                return
            }
            probe(wifi) { ok ->
                val now = System.currentTimeMillis()
                if (ok) {
                    wifiProbeOkAt = now
                    wifiFailures = 0
                    wifiRecoverySuccesses++
                    if (wifiRecoverySuccesses >= WIFI_RECOVERY_SUCCESSES &&
                        now - failoverSince >= CELLULAR_MIN_HOLD_MS
                    ) {
                        releaseCellular("wifi_recovered")
                    }
                } else {
                    wifiProbeFailAt = now
                    wifiRecoverySuccesses = 0
                }
            }
            return
        }

        if (wifi == null) {
            wifiFailures = 0
            wifiRecoverySuccesses = 0
            updateMode(defaultRouteMode())
            return
        }

        probe(wifi) { ok ->
            val now = System.currentTimeMillis()
            if (ok) {
                wifiProbeOkAt = now
                wifiFailures = 0
                updateMode("wifi")
            } else {
                wifiProbeFailAt = now
                wifiFailures = (wifiFailures + 1).coerceAtMost(99)
                updateMode("wifi_server_unreachable")
                if (wifiFailures >= WIFI_FAILURES_TO_FAILOVER) requestCellular()
            }
        }
    }

    private fun requestCellular() {
        if (stopped.get() || failoverActive || cellularCallback != null || hasActiveVpn()) return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                cellularAvailable = true
                cellularNetwork = network
                probe(network) { ok ->
                    if (!ok || stopped.get() || hasActiveVpn() || failoverActive) return@probe
                    val bound = try { cm.bindProcessToNetwork(network) } catch (_: Exception) { false }
                    if (bound) {
                        failoverActive = true
                        failoverSince = System.currentTimeMillis()
                        wifiRecoverySuccesses = 0
                        updateMode("cellular_failover", forceNotify = true, reason = "cellular_failover")
                    }
                }
            }

            override fun onLost(network: Network) {
                if (cellularNetwork != network) return
                cellularAvailable = false
                cellularNetwork = null
                if (failoverActive) {
                    try { cm.bindProcessToNetwork(null) } catch (_: Exception) {}
                    failoverActive = false
                    failoverSince = 0L
                    wifiRecoverySuccesses = 0
                    cleanupCellularCallback()
                    updateMode(defaultRouteMode(), forceNotify = true, reason = "cellular_lost")
                }
            }

            override fun onUnavailable() {
                cellularAvailable = false
                cleanupCellularCallback()
                updateMode("wifi_server_unreachable")
            }
        }

        cellularCallback = callback
        try {
            cm.requestNetwork(request, callback)
            updateMode("requesting_cellular")
            val timeout = Runnable {
                if (!failoverActive) {
                    cellularAvailable = false
                    cleanupCellularCallback()
                    updateMode("wifi_server_unreachable")
                }
            }
            requestTimeout = timeout
            main.postDelayed(timeout, CELLULAR_REQUEST_TIMEOUT_MS)
        } catch (_: Exception) {
            cellularCallback = null
            updateMode("wifi_server_unreachable")
        }
    }

    private fun releaseCellular(reason: String) {
        requestTimeout?.let { main.removeCallbacks(it) }
        requestTimeout = null
        try { cm.bindProcessToNetwork(null) } catch (_: Exception) {}
        failoverActive = false
        failoverSince = 0L
        wifiRecoverySuccesses = 0
        cleanupCellularCallback()
        cellularNetwork = null
        cellularAvailable = false
        updateMode(defaultRouteMode(), forceNotify = true, reason = reason)
    }

    private fun cleanupCellularCallback() {
        val cb = cellularCallback ?: return
        cellularCallback = null
        try { cm.unregisterNetworkCallback(cb) } catch (_: Exception) {}
    }

    private fun probe(network: Network, callback: (Boolean) -> Unit) {
        try {
            io.execute {
                val ok = try {
                    val connection = network.openConnection(URL(PROBE_URL)) as HttpURLConnection
                    connection.requestMethod = "GET"
                    connection.connectTimeout = PROBE_CONNECT_TIMEOUT_MS
                    connection.readTimeout = PROBE_READ_TIMEOUT_MS
                    connection.useCaches = false
                    connection.setRequestProperty("Connection", "close")
                    val code = connection.responseCode
                    try { connection.inputStream?.close() } catch (_: Exception) {}
                    try { connection.errorStream?.close() } catch (_: Exception) {}
                    connection.disconnect()
                    code in 200..499
                } catch (_: Exception) {
                    false
                }
                if (!stopped.get()) main.post { callback(ok) }
            }
        } catch (_: RejectedExecutionException) {
            // A late network callback can race service shutdown. Never crash the
            // foreground service because the probe executor is already closed.
        }
    }

    private fun findNetwork(transport: Int): Network? = try {
        cm.allNetworks.firstOrNull { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@firstOrNull false
            caps.hasTransport(transport) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    } catch (_: Exception) {
        null
    }

    private fun hasActiveVpn(): Boolean = try {
        val active = cm.activeNetwork ?: return false
        cm.getNetworkCapabilities(active)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    } catch (_: Exception) {
        false
    }

    private fun defaultRouteMode(): String = try {
        val active = cm.activeNetwork ?: return "offline"
        val caps = cm.getNetworkCapabilities(active) ?: return "unknown"
        when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
    } catch (_: Exception) {
        "unknown"
    }

    private fun currentRouteMode(): String =
        if (failoverActive) "cellular_failover" else currentMode.ifBlank { defaultRouteMode() }

    private fun updateMode(mode: String, forceNotify: Boolean = false, reason: String = mode) {
        val changed = mode != currentMode
        currentMode = mode
        if (changed || forceNotify) onRouteChanged(reason)
    }

    companion object {
        private const val PROBE_URL = "https://firestore.googleapis.com/"
        private const val INITIAL_PROBE_DELAY_MS = 5_000L
        private const val HEALTHY_PROBE_INTERVAL_MS = 60_000L
        private const val DEFAULT_PROBE_INTERVAL_MS = 60_000L
        private const val DEGRADED_PROBE_INTERVAL_MS = 15_000L
        private const val PROBE_CONNECT_TIMEOUT_MS = 5_000
        private const val PROBE_READ_TIMEOUT_MS = 5_000
        private const val WIFI_FAILURES_TO_FAILOVER = 3
        private const val WIFI_RECOVERY_SUCCESSES = 3
        private const val CELLULAR_MIN_HOLD_MS = 2 * 60_000L
        private const val CELLULAR_REQUEST_TIMEOUT_MS = 20_000L
    }
}
