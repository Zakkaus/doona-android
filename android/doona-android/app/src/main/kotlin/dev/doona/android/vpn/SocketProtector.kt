package dev.doona.android.vpn

/**
 * Keeps one of honk's outgoing sockets off the VPN, as `VpnService.protect` does. honk calls it from its own threads for
 * every socket it opens to the outside, and that socket fails when it returns false.
 */
fun interface SocketProtector {
    fun protect(fd: Int): Boolean
}
