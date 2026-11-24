package dev.doona.android.vpn

import java.io.Closeable

/** A tun descriptor the controller lends to the core and closes once `start` returns. */
internal interface Tun : Closeable {
    val fd: Int
}

/** What [TunnelController] needs from Android to build a tunnel; [DoonaVpnService] implements it. */
internal interface TunHost {
    val selfPackage: String

    /** The tun's MTU, which the core's start config repeats; only a debug build may set another than the default. */
    val mtu: Int get() = StartConfigDto.MTU

    /** The subset of [packages] installed for this user and visible to doona. */
    fun installed(packages: Set<String>): Set<String>

    /** The default network's resolvers, read before [establish]. */
    fun bootstrapDns(): List<String>

    /** Null when the service is not in the foreground or VPN consent is missing. */
    fun establish(routing: AppRouting, mtu: Int): Tun?
}
