package dev.doona.android.vpn

/** An Android policy that prevents the tunnel from running. */
enum class VpnStartBlock {
    /** Lockdown needs per-socket protection, not only excluding doona from its own routing. */
    Lockdown,

    /** Android refused promoting the VPN service to the foreground. */
    ForegroundStartNotAllowed,
}
