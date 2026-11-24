package dev.doona.android.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dev.doona.android.engine.NetworkState
import dev.doona.android.engine.NetworkTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.net.InetAddress

class NetworkStateSource internal constructor(
    state: StateFlow<NetworkState>,
    /** Android detected a sign-in page; a failed connectivity probe alone does not set this. */
    val captivePortal: Flow<Boolean>,
) : StateFlow<NetworkState> by state

private data class DefaultNetwork(val state: NetworkState, val captivePortal: Boolean)

private val offline = DefaultNetwork(NetworkState(), captivePortal = false)

internal fun ownAddresses(context: Context): List<InetAddress> {
    val connectivity = context.getSystemService(ConnectivityManager::class.java)
    val active = connectivity.activeNetwork ?: return emptyList()
    return connectivity.getLinkProperties(active)?.linkAddresses.orEmpty().map { it.address }
}

internal fun networkState(context: Context, scope: CoroutineScope): NetworkStateSource {
    val updates = callbackFlow {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
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
    }.conflate().stateIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000, replayExpirationMillis = 0), offline)
    return NetworkStateSource(
        updates.map { it.state }.stateIn(scope, SharingStarted.WhileSubscribed(replayExpirationMillis = 0), offline.state),
        updates.map { it.captivePortal }.distinctUntilChanged(),
    )
}

private fun NetworkCapabilities.toState(): DefaultNetwork {
    if (!hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return offline
    val transport = when {
        hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkTransport.WiFi
        hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkTransport.Cellular
        else -> NetworkTransport.Other
    }
    return DefaultNetwork(
        NetworkState(
            transport,
            metered = !hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            validated = hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
        ),
        captivePortal = hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL),
    )
}
