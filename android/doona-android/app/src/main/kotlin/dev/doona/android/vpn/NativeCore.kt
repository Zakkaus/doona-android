package dev.doona.android.vpn

/**
 * `libdoonacore.so`, loaded when this object is first used. The symbols are `Java_dev_doona_android_vpn_NativeCore_<name>`.
 * Kotlin forbids `@JvmStatic` on an override, so these are instance methods: the second JNI argument is this object,
 * not its class, and the core must not use it as a class.
 */
internal object NativeCore : CoreBridge {
    init {
        System.loadLibrary("doonacore")
    }

    external override fun init(dataDir: String): String

    external override fun start(tunFd: Int, configJson: String)

    external override fun stop()

    external override fun snapshot(sinceRevision: Long): String

    external override fun call(command: String, argsJson: String): String
}
