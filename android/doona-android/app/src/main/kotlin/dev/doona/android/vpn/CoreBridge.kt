package dev.doona.android.vpn

/**
 * The Rust core's five JNI functions (`plans/v1/core-api.md`, schema 1), as an interface so JVM tests can fake the
 * core. Payloads are JSON; `vpn/CoreDto.kt` types them.
 */
interface CoreBridge {
    /** Once per process before anything else; idempotent. Returns `{"schema":1}`. */
    fun init(dataDir: String): String

    /**
     * Blocks until the data path is up, at most 10 s. [tunFd] is borrowed: the core duplicates it first, and the caller
     * closes its own descriptor once this returns or throws. Throws [IllegalStateException] with an error code.
     */
    fun start(tunFd: Int, configJson: String)

    /** Returns within 1 s; safe when not running. */
    fun stop()

    /** Cheap; the catalog is present only when the core's revision is above [sinceRevision]. */
    fun snapshot(sinceRevision: Long): String

    /** Returns `{"ok":…}` or `{"error":{"code":…}}`. Call it on `Dispatchers.IO`. */
    fun call(command: String, argsJson: String): String
}
