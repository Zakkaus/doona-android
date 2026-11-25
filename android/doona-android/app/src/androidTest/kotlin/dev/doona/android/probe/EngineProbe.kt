package dev.doona.android.probe

/**
 * The embedded honk engine of `libdoonacore.so`, built only with the core's `engine-probe` feature
 * (`-Pdoona.engineProbe=true`). [decide] builds the engine from the core's runtime fixture, committed to a
 * store under [dir], and returns `outbound mode chain` for one TCP flow; `chain` is the first leaf's path joined
 * by `>`, `direct` and `block` being leaves of their own.
 */
internal object EngineProbe {
    init {
        System.loadLibrary("doonacore")
    }

    /** [mode] is `rule`, `global` or `direct`; [global] is empty, `group:<name>` or `node:<name>`. */
    external fun decide(dir: String, mode: String, global: String, destination: String, domain: String): String
}
