package dev.doona.android.data.network

import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.engine.NetworkState
import dev.doona.android.engine.NetworkTransport
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities

@RunWith(AndroidJUnit4::class)
class NetworkStateSourceTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val connectivity = app.getSystemService(ConnectivityManager::class.java)
    private val manager = shadowOf(connectivity)
    private val existing = manager.networkCallbacks.toSet()
    private val wifi = ShadowNetwork.newInstance(100)

    @Test
    fun losingWifiGoesOfflineUntilCellularTakesOver() = runTest {
        val state = observeNetwork()
        val callback = (manager.networkCallbacks - existing).single()
        val wifiCaps = capabilities(NetworkCapabilities.TRANSPORT_WIFI, NetworkCapabilities.NET_CAPABILITY_NOT_METERED, NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        callback.onCapabilitiesChanged(wifi, wifiCaps)
        testScheduler.runCurrent()
        assertEquals(NetworkState(NetworkTransport.WiFi, metered = false, validated = true), state.value)
        callback.onLost(wifi)
        testScheduler.runCurrent()
        assertEquals(NetworkState(), state.value)
        callback.onCapabilitiesChanged(ShadowNetwork.newInstance(101), capabilities(NetworkCapabilities.TRANSPORT_CELLULAR))
        testScheduler.runCurrent()
        assertEquals(NetworkState(NetworkTransport.Cellular, metered = true, validated = false), state.value)
    }

    @Test
    fun wifiWorksInAirplaneMode() = runTest {
        Settings.Global.putInt(app.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 1)
        val state = observeNetwork()
        val wifiCaps = capabilities(NetworkCapabilities.TRANSPORT_WIFI, NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        (manager.networkCallbacks - existing).single().onCapabilitiesChanged(wifi, wifiCaps)
        testScheduler.runCurrent()
        assertTrue(state.value.online)
        assertEquals(NetworkTransport.WiFi, state.value.transport)
    }

    @Test
    fun captivePortalAndFailedProbeStayOnlineButUnvalidated() = runTest {
        val state = observeNetwork()
        val signIn = mutableListOf<Boolean>()
        backgroundScope.launch { state.captivePortal.collect { signIn += it } }
        testScheduler.runCurrent()
        val callback = (manager.networkCallbacks - existing).single()
        val caps = capabilities(NetworkCapabilities.TRANSPORT_WIFI, NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
        callback.onCapabilitiesChanged(wifi, caps)
        testScheduler.runCurrent()
        assertTrue(state.value.online)
        assertFalse(state.value.validated)
        assertEquals(listOf(false, true), signIn)

        shadowOf(caps).removeCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
        callback.onCapabilitiesChanged(wifi, caps)
        testScheduler.runCurrent()
        assertFalse(state.value.validated)
        assertEquals(listOf(false, true, false), signIn)

        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        callback.onCapabilitiesChanged(wifi, caps)
        testScheduler.runCurrent()
        assertTrue(state.value.validated)

        shadowOf(caps).removeCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        callback.onCapabilitiesChanged(wifi, caps)
        testScheduler.runCurrent()
        assertTrue(state.value.online)
        assertFalse(state.value.validated)
        assertFalse(signIn.last())

        shadowOf(caps).removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        callback.onCapabilitiesChanged(wifi, caps)
        testScheduler.runCurrent()
        assertFalse(state.value.online)
    }

    @Test
    fun observersShareOneCallbackUntilUnsubscribedOrOwnerCancelled() = runTest {
        val state = networkState(app, backgroundScope)
        testScheduler.runCurrent()
        assertEquals(existing, manager.networkCallbacks)
        val first = backgroundScope.launch { state.collect {} }
        val second = backgroundScope.launch { state.captivePortal.collect {} }
        testScheduler.runCurrent()
        val callback = (manager.networkCallbacks - existing).single()
        first.cancel()
        testScheduler.advanceTimeBy(5_001)
        assertEquals(setOf(callback), manager.networkCallbacks - existing)
        second.cancel()
        testScheduler.advanceTimeBy(4_999)
        assertEquals(setOf(callback), manager.networkCallbacks - existing)
        testScheduler.advanceTimeBy(2)
        assertEquals(existing, manager.networkCallbacks)

        backgroundScope.launch { state.collect {} }
        testScheduler.runCurrent()
        assertEquals(1, (manager.networkCallbacks - existing).size)
        backgroundScope.cancel()
        testScheduler.runCurrent()
        assertEquals(existing, manager.networkCallbacks)
    }

    private fun TestScope.observeNetwork(): NetworkStateSource {
        val state = networkState(app, backgroundScope)
        backgroundScope.launch { state.collect {} }
        testScheduler.runCurrent()
        return state
    }

    private fun capabilities(transport: Int, vararg extra: Int): NetworkCapabilities {
        val caps = ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).addTransportType(transport)
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        extra.forEach { shadowOf(caps).addCapability(it) }
        return caps
    }
}
