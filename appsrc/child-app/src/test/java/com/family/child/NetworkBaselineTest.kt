package com.family.child

import android.content.Context
import android.net.*
import android.os.Looper
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkInfo
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class NetworkBaselineTest {
    private lateinit var context: Context
    private lateinit var cm: ConnectivityManager
    @Before fun setup() { context=RuntimeEnvironment.getApplication(); cm=context.getSystemService(ConnectivityManager::class.java); shadowOf(cm).clearAllNetworks(); shadowOf(cm).setActiveNetworkInfo(null) }
    private fun add(id: Int, type: Int, transport: Int): Network {
        val n=ShadowNetwork.newInstance(id); val info=ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED,type,0,true,true)
        shadowOf(cm).addNetwork(n,info)
        val caps=ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).addTransportType(transport); shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(cm).setNetworkCapabilities(n,caps)
        return n
    }
    private fun invoke(manager: NetworkFailoverManager, name: String) { manager.javaClass.getDeclaredMethod(name).apply { isAccessible=true }.invoke(manager) }
    private fun await(predicate: () -> Boolean) {
        val end=System.nanoTime()+3_000_000_000
        while (!predicate() && System.nanoTime()<end) { Thread.sleep(10); shadowOf(Looper.getMainLooper()).idle() }
        assertTrue("asynchronous network state did not arrive",predicate())
    }
    @Test fun bothNetworksOfflineDoesNotBindAndJournalRemainsAvailable() {
        val manager=NetworkFailoverManager(context,{ false },{})
        try { invoke(manager,"evaluate"); assertEquals("offline",manager.snapshot().routeMode); assertFalse(manager.snapshot().cellularFailoverActive); assertNull(cm.boundNetworkForProcess)
            PendingStore(context).useStore { it.insert("{\"type\":\"network_offline\",\"id\":\"offline\",\"time\":1}"); assertEquals(1,it.count()) }
        } finally { manager.stop() }
    }
    @Test fun badWifiGoodCellularBindsAndLostCellularCleansUp() {
        val wifi=add(1,ConnectivityManager.TYPE_WIFI,NetworkCapabilities.TRANSPORT_WIFI)
        val cellular=add(2,ConnectivityManager.TYPE_MOBILE,NetworkCapabilities.TRANSPORT_CELLULAR)
        val manager=NetworkFailoverManager(context,{ it==cellular },{})
        try {
            repeat(3) { val target=it+1; invoke(manager,"evaluate"); await { manager.snapshot().wifiServerFailureCount>=target } }
            val callback=shadowOf(cm).networkCallbacks.single()
            callback.onAvailable(cellular); await { manager.snapshot().cellularFailoverActive }
            assertEquals(cellular,cm.boundNetworkForProcess)
            callback.onLost(cellular); shadowOf(Looper.getMainLooper()).idle()
            assertFalse(manager.snapshot().cellularFailoverActive); assertNull(cm.boundNetworkForProcess)
            assertNotNull(cm.getNetworkCapabilities(wifi))
        } finally { manager.stop() }
    }
    @Test fun unavailableCallbackFromOldRequestCannotClearReplacement() {
        add(1,ConnectivityManager.TYPE_WIFI,NetworkCapabilities.TRANSPORT_WIFI)
        val manager=NetworkFailoverManager(context,{ false },{})
        try {
            invoke(manager,"requestCellular"); val old=shadowOf(cm).networkCallbacks.single()
            old.onUnavailable(); shadowOf(Looper.getMainLooper()).idle(); invoke(manager,"requestCellular")
            val replacement=shadowOf(cm).networkCallbacks.single(); assertNotSame(old,replacement)
            old.onUnavailable(); shadowOf(Looper.getMainLooper()).idle()
            assertTrue(shadowOf(cm).networkCallbacks.contains(replacement))
        } finally { manager.stop() }
    }
    @Test fun vpnCancelsPendingCellularRequestWithoutBypass() {
        add(1,ConnectivityManager.TYPE_WIFI,NetworkCapabilities.TRANSPORT_WIFI)
        val manager=NetworkFailoverManager(context,{ false },{})
        try {
            invoke(manager,"requestCellular"); assertEquals(1,shadowOf(cm).networkCallbacks.size)
            add(17,ConnectivityManager.TYPE_VPN,NetworkCapabilities.TRANSPORT_VPN)
            invoke(manager,"evaluate"); assertEquals("vpn",manager.snapshot().routeMode)
            assertTrue(shadowOf(cm).networkCallbacks.isEmpty()); assertNull(cm.boundNetworkForProcess)
        } finally { manager.stop() }
    }
}
