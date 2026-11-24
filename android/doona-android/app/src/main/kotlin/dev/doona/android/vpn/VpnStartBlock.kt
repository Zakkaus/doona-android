package dev.doona.android.vpn

/** An Android policy that must change before the tunnel can start. */
enum class VpnStartBlock {
    /** Lockdown needs per-socket protection, not only excluding doona from its own routing. */
    Lockdown,
}
