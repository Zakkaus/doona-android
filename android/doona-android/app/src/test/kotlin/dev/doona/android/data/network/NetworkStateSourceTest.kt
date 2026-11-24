package dev.doona.android.data.network

import android.app.Application
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.engine.NetworkState
import dev.doona.android.engine.NetworkTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowConnectivityManager
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import java.net.InetAddress

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class NetworkStateSourceTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val connectivity = app.getSystemService(ConnectivityManager::class.java)
    private val manager = shadowOf(connectivity)
    private val existing = manager.networkCallbacks.toSet()
    private val wifi = ShadowNetwork.newInstance(100)

    @Test
    @Config(shadows = [BestMatchingConnectivity::class])
    fun bootstrapDnsRequestOnlyMatchesNonVpnInternetNetworks() = runTest {
        val seen = mutableListOf<List<String>>()
        val follow = launch { connectivity.followBootstrapDns { seen += it } }
        testScheduler.runCurrent()

        val request = Shadow.extract<BestMatchingConnectivity>(connectivity).request
        val defaults = NetworkRequest.Builder().build().capabilities
        val wifiInternet = capabilities(NetworkCapabilities.TRANSPORT_WIFI, *defaults)
        val wifiLan = capabilities(NetworkCapabilities.TRANSPORT_WIFI, *defaults).also {
            shadowOf(it).removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
        val vpnInternet = capabilities(NetworkCapabilities.TRANSPORT_VPN, *defaults).also {
            shadowOf(it).removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        }
        assertTrue(request.canBeSatisfiedBy(wifiInternet))
        assertFalse(request.canBeSatisfiedBy(wifiLan))
        assertFalse(request.canBeSatisfiedBy(vpnInternet))

        val callback = (manager.networkCallbacks - existing).single()
        callback.onAvailable(wifi)
        callback.onCapabilitiesChanged(wifi, wifiInternet)
        callback.onLinkPropertiesChanged(wifi, LinkProperties().apply { setDnsServers(listOf(InetAddress.getByName("192.0.2.53"))) })
        assertEquals(listOf(listOf("192.0.2.53")), seen)
        follow.cancelAndJoin()
    }

    @Implements(ConnectivityManager::class)
    class BestMatchingConnectivity : ShadowConnectivityManager() {
        lateinit var request: NetworkRequest
            private set

        @Implementation
        override fun registerBestMatchingNetworkCallback(request: NetworkRequest, callback: ConnectivityManager.NetworkCallback, handler: Handler) {
            this.request = request
            super.registerBestMatchingNetworkCallback(request, callback, handler)
        }
    }

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
    fun nonDefaultNetworksCanNeedSignInWhileCellularIsValidated() = runTest {
        val source = networkState(app, backgroundScope)
        backgroundScope.launch { source.state.collect {} }
        testScheduler.runCurrent()
        val defaultCallback = (manager.networkCallbacks - existing).single()
        defaultCallback.onCapabilitiesChanged(
            checkNotNull(connectivity.activeNetwork),
            capabilities(NetworkCapabilities.TRANSPORT_CELLULAR, NetworkCapabilities.NET_CAPABILITY_VALIDATED),
        )
        backgroundScope.launch { source.captivePortal.collect {} }
        testScheduler.runCurrent()

        val portal = capabilities(NetworkCapabilities.TRANSPORT_WIFI, NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
        val unvalidated = capabilities(NetworkCapabilities.TRANSPORT_WIFI)
        val anotherWifi = ShadowNetwork.newInstance(102)
        publishCapabilities(wifi, unvalidated)
        assertFalse(source.captivePortal.value)
        publishCapabilities(wifi, portal)
        assertEquals(NetworkState(NetworkTransport.Cellular, metered = true, validated = true), source.state.value)
        assertTrue(source.captivePortal.value)

        publishCapabilities(anotherWifi, portal)
        publishCapabilities(wifi, unvalidated)
        assertTrue(source.captivePortal.value)
        publishCapabilities(anotherWifi, unvalidated)
        assertFalse(source.captivePortal.value)
    }

    private fun TestScope.publishCapabilities(network: Network, capabilities: NetworkCapabilities) {
        manager.setNetworkCapabilities(network, capabilities)
        shadowOf(Looper.getMainLooper()).idle()
        testScheduler.runCurrent()
    }

    @Test
    fun observersShareOneCallbackUntilUnsubscribedOrOwnerCancelled() = runTest {
        val state = networkState(app, backgroundScope)
        testScheduler.runCurrent()
        assertEquals(existing, manager.networkCallbacks)
        val first = backgroundScope.launch { state.state.collect {} }
        val second = backgroundScope.launch { state.state.collect {} }
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

        backgroundScope.launch { state.captivePortal.collect {} }
        testScheduler.runCurrent()
        assertEquals(1, (manager.networkCallbacks - existing).size)
        backgroundScope.cancel()
        testScheduler.runCurrent()
        assertEquals(existing, manager.networkCallbacks)
    }

    private fun TestScope.observeNetwork(): StateFlow<NetworkState> {
        val state = networkState(app, backgroundScope).state
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
