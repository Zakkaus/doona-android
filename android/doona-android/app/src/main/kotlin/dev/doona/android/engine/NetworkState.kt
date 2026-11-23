package dev.doona.android.engine

/** The app's validated default connection, or no transport when offline. */
data class NetworkState(val transport: NetworkTransport? = null, val metered: Boolean = false) {
    val online: Boolean get() = transport != null
}

enum class NetworkTransport { WiFi, Cellular, Other }
