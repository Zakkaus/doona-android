package dev.doona.android.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import dev.doona.android.engine.NetworkState
import dev.doona.android.engine.NetworkTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.stateIn
import java.net.Inet6Address
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

internal fun ConnectivityManager.activeBootstrapDns(): List<String> {
    val active = activeNetwork ?: return emptyList()
    if (getNetworkCapabilities(active)?.isBootstrapNetwork() != true) return emptyList()
    return getLinkProperties(active)?.bootstrapServers().orEmpty()
}

internal suspend fun ConnectivityManager.followBootstrapDns(refresh: (List<String>) -> Unit) {
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            refresh(linkProperties.bootstrapServers())
        }
    }
    // Use Android's network ranking without asking it to bring up a different connection. NOT_VPN
    // comes from the Builder's defaults, which is why it is not added here.
    val request = NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .build()
    registerBestMatchingNetworkCallback(request, callback, Handler(Looper.getMainLooper()))
    try {
        awaitCancellation()
    } finally {
        unregisterNetworkCallback(callback)
    }
}

private fun NetworkCapabilities.isBootstrapNetwork() =
    hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && !hasTransport(NetworkCapabilities.TRANSPORT_VPN)

/** Keeps link-local interface names for the core to resolve when it consumes the snapshot. */
private fun LinkProperties.bootstrapServers(): List<String> {
    val iface = interfaceName
    return dnsServers.mapNotNull { server ->
        val address = server.hostAddress ?: return@mapNotNull null
        if (server is Inet6Address && server.isLinkLocalAddress && iface != null) "${address.substringBefore('%')}%$iface" else address
    }
}

/**
 * [NetworkStateSource.state] runs for the life of [scope] and starts from the current default network, so its value is
 * right without a collector. The captive-portal flag registers its callback only while observed.
 */
internal fun networkState(context: Context, scope: CoroutineScope): NetworkStateSource {
    val connectivity = context.getSystemService(ConnectivityManager::class.java)
    return NetworkStateSource(
        defaultNetwork(connectivity).stateIn(scope, SharingStarted.Eagerly, connectivity.defaultState()),
        captivePortals(connectivity).stateIn(scope, whileObserved, false),
    )
}

private fun ConnectivityManager.defaultState(): NetworkState =
    activeNetwork?.let(::getNetworkCapabilities)?.toState() ?: offline

private fun defaultNetwork(connectivity: ConnectivityManager) = callbackFlow {
    val callback = object : ConnectivityManager.NetworkCallback() {
        // Callbacks arrive on one handler thread. Android can report the loss of the old default network after the new
        // one is up, so only the loss of the current default network means offline.
        private var current: Network? = null

        override fun onAvailable(network: Network) {
            current = network
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            current = network
            trySend(networkCapabilities.toState())
        }

        override fun onLost(network: Network) {
            if (network != current) return
            current = null
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
