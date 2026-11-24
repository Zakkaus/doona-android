package dev.doona.android.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dev.doona.android.engine.NetworkState
import dev.doona.android.engine.NetworkTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.stateIn
import java.net.InetAddress

class NetworkStateSource internal constructor(
    val state: StateFlow<NetworkState>,
    /** Android detected a sign-in page on a network, which need not be the default. */
    val captivePortal: StateFlow<Boolean>,
)

private val offline = NetworkState()
private val whileObserved = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000, replayExpirationMillis = 0)

internal fun ownAddresses(context: Context): List<InetAddress> {
    val connectivity = context.getSystemService(ConnectivityManager::class.java)
    val active = connectivity.activeNetwork ?: return emptyList()
    return connectivity.getLinkProperties(active)?.linkAddresses.orEmpty().map { it.address }
}

internal fun networkState(context: Context, scope: CoroutineScope): NetworkStateSource {
    val connectivity = context.getSystemService(ConnectivityManager::class.java)
    return NetworkStateSource(
        defaultNetwork(connectivity).stateIn(scope, whileObserved, offline),
        captivePortals(connectivity).stateIn(scope, whileObserved, false),
    )
}

private fun defaultNetwork(connectivity: ConnectivityManager) = callbackFlow {
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            trySend(networkCapabilities.toState())
        }

        override fun onLost(network: Network) {
            trySend(offline)
        }
    }
    connectivity.registerDefaultNetworkCallback(callback)
    awaitClose { connectivity.unregisterNetworkCallback(callback) }
}.conflate()

private fun captivePortals(connectivity: ConnectivityManager) = callbackFlow {
    val networks = mutableSetOf<Network>()
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (networks.add(network) && networks.size == 1) trySend(true)
        }

        // Matching stops when the network goes away or Android clears its captive-portal capability.
        override fun onLost(network: Network) {
            if (networks.remove(network) && networks.isEmpty()) trySend(false)
        }
    }
    val request = NetworkRequest.Builder().clearCapabilities().addCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL).build()
    connectivity.registerNetworkCallback(request, callback)
    awaitClose { connectivity.unregisterNetworkCallback(callback) }
}.conflate()

private fun NetworkCapabilities.toState(): NetworkState {
    if (!hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return offline
    val transport = when {
        hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkTransport.WiFi
        hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkTransport.Cellular
        else -> NetworkTransport.Other
    }
    return NetworkState(
        transport,
        metered = !hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
        validated = hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
    )
}
