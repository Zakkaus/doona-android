package dev.doona.android.data.network

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.annotation.MainThread
import androidx.core.content.ContextCompat
import dev.doona.android.engine.NetworkState
import dev.doona.android.engine.NetworkTransport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Construct and close on the main thread at the owner's lifecycle boundaries. */
class NetworkStateSource @MainThread constructor(context: Context) : AutoCloseable {
    private val app = context.applicationContext
    private val connectivity = app.getSystemService(ConnectivityManager::class.java)
    private val offline = NetworkState()
    private val _state = MutableStateFlow(offline)
    val state = _state.asStateFlow()
    private var connection = offline
    private var airplane = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            connection = networkCapabilities.toState()
            publish()
        }

        override fun onLost(network: Network) {
            connection = offline
            publish()
        }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            readAirplaneMode()
        }
    }

    init {
        val filter = IntentFilter(Intent.ACTION_AIRPLANE_MODE_CHANGED)
        ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        connectivity.registerDefaultNetworkCallback(callback, Handler(Looper.getMainLooper()))
        connection = connectivity.activeNetwork?.let(connectivity::getNetworkCapabilities)?.toState() ?: offline
        readAirplaneMode()
    }

    private fun readAirplaneMode() {
        airplane = Settings.Global.getInt(app.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
        publish()
    }

    private fun publish() {
        _state.value = if (airplane) offline else connection
    }

    @MainThread
    override fun close() {
        connectivity.unregisterNetworkCallback(callback)
        app.unregisterReceiver(receiver)
    }

    private fun NetworkCapabilities.toState(): NetworkState {
        if (!hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return offline
        val transport = when {
            hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkTransport.WiFi
            hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkTransport.Cellular
            else -> NetworkTransport.Other
        }
        return NetworkState(transport, metered = !hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
    }
}
