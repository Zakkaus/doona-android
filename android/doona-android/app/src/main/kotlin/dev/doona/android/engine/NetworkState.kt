package dev.doona.android.engine

/** The default network with internet capability, whether or not Android's connectivity probe succeeded. */
data class NetworkState(val transport: NetworkTransport? = null, val metered: Boolean = false, val validated: Boolean = false) {
    val online: Boolean get() = transport != null
}

enum class NetworkTransport { WiFi, Cellular, Other }
