package dev.doona.android.vpn

/**
 * `libdoonacore.so`, loaded when this object is first used. The symbols are `Java_dev_doona_android_vpn_NativeCore_<name>`.
 * Kotlin forbids `@JvmStatic` on an override, so these are instance methods: the second JNI argument is this object,
 * not its class, and the core must not use it as a class.
 */
internal object NativeCore : CoreBridge, NativeApi {
    init {
        System.loadLibrary("doonacore")
    }

    external override fun init(dataDir: String, optionsJson: String): String

    external override fun start(tunFd: Int, configJson: String)

    external override fun stop()

    external override fun snapshot(sinceRevision: Long): String

    external override fun call(command: String, argsJson: String): String

    override fun call(method: String, path: String, body: String?, headers: Map<String, String>) = api(method, path, body, headers)

    private external fun api(method: String, path: String, body: String?, headers: Map<String, String>): NativeResponse
}

/**
 * Gets the core from [load] on its first call instead of where it is built. Built with `{ NativeCore }`, that first call
 * is the opener's `init` on IO, where a missing or broken library becomes a failed open instead of a crash.
 */
internal class DeferredCore(load: () -> CoreBridge) : CoreBridge {
    private val core by lazy(load)

    override fun init(dataDir: String, optionsJson: String) = core.init(dataDir, optionsJson)

    override fun start(tunFd: Int, configJson: String) = core.start(tunFd, configJson)

    override fun stop() = core.stop()

    override fun snapshot(sinceRevision: Long) = core.snapshot(sinceRevision)

    override fun call(command: String, argsJson: String) = core.call(command, argsJson)
}
