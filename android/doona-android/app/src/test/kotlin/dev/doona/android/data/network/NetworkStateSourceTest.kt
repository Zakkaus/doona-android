package dev.doona.android.data.network

import android.app.Application
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Looper
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.engine.NetworkState
import dev.doona.android.engine.NetworkTransport
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

    @Test
    fun wifiToAirplaneToCellularAndOwnerClose() {
        val wifi = ShadowNetwork.newInstance(100)
        val cellular = ShadowNetwork.newInstance(101)
        val callbacksBefore = manager.networkCallbacks.toSet()
        val wifiCaps = capabilities(NetworkCapabilities.TRANSPORT_WIFI, metered = false)
        manager.setNetworkCapabilities(connectivity.activeNetwork, wifiCaps)
        val source = NetworkStateSource(app)
        source.use {
            val callback = (manager.networkCallbacks - callbacksBefore).single()
            val wifiState = NetworkState(NetworkTransport.WiFi, metered = false)
            assertEquals(wifiState, source.state.value)
            assertTrue(source.state.value.online)

            airplaneMode(true)
            assertFalse(source.state.value.online)
            callback.onCapabilitiesChanged(wifi, wifiCaps)
            assertEquals(NetworkState(), source.state.value)
            callback.onLost(wifi)
            airplaneMode(false)
            assertEquals(NetworkState(), source.state.value)

            callback.onCapabilitiesChanged(cellular, capabilities(NetworkCapabilities.TRANSPORT_CELLULAR, metered = true))
            assertEquals(NetworkState(NetworkTransport.Cellular, metered = true), source.state.value)
        }
        assertEquals(callbacksBefore, manager.networkCallbacks)
        airplaneMode(true)
        assertEquals(NetworkState(NetworkTransport.Cellular, metered = true), source.state.value)
    }

    private fun airplaneMode(enabled: Boolean) {
        Settings.Global.putInt(app.contentResolver, Settings.Global.AIRPLANE_MODE_ON, if (enabled) 1 else 0)
        app.sendBroadcast(Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED).putExtra("state", enabled))
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun capabilities(transport: Int, metered: Boolean): NetworkCapabilities {
        val caps = ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).addTransportType(transport)
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        if (!metered) shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        return caps
    }
}
