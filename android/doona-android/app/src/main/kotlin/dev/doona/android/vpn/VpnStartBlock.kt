package dev.doona.android.vpn

/** An Android policy that prevents the tunnel from running. */
enum class VpnStartBlock {
    /** Android refused promoting the VPN service to the foreground. */
    ForegroundStartNotAllowed,
}
