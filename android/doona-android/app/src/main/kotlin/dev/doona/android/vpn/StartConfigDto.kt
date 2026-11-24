package dev.doona.android.vpn

import kotlinx.serialization.Serializable

/** The tun the service builds uses the same values. */
@Serializable
internal data class StartConfigDto(val mtu: Int, val ipv4: String, val dns: String, val bootstrapDns: List<String>) {
    companion object {
        const val MTU = 65535
        const val IPV4 = "172.19.0.1"
        const val IPV4_PREFIX = 30
        const val DNS = "172.19.0.2"

        fun of(bootstrapDns: List<String>, mtu: Int = MTU) = StartConfigDto(mtu, IPV4, DNS, bootstrapDns)
    }
}
