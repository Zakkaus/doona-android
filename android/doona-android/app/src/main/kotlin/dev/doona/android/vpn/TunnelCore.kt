package dev.doona.android.vpn

/** The Rust core's TUN lifecycle: honk runs the data path on doona's TUN descriptor. Its HTTP API is [NativeApi]. */
interface TunnelCore {
    /**
     * Blocks until the data path is up. [tunFd] is borrowed: the core duplicates it first, and the caller closes its own
     * descriptor once this returns or throws. [configPath] is honk's config file and [dataRoot] the directory honk keeps
     * its state database under; every socket honk opens to the outside goes through [protector] first. Throws
     * [IllegalStateException] with an error code.
     */
    fun start(tunFd: Int, configPath: String, dataRoot: String, protector: SocketProtector)

    /** Returns within 1 s; safe when not running. */
    fun stop()

    /** Android's default network changed while the core runs. */
    fun networkChanged()
}
