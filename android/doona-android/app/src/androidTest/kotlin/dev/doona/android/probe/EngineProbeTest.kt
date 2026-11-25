package dev.doona.android.probe

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The same decisions as `crates/doona-core/tests/honk_runtime.rs`, made by the arm64 library on a phone. */
@RunWith(AndroidJUnit4::class)
class EngineProbeTest {
    private val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "engine-probe")

    private fun decide(mode: String, global: String, destination: String, domain: String = ""): String {
        dir.deleteRecursively()
        return EngineProbe.decide(dir.path, mode, global, destination, domain)
    }

    @Test
    fun ruleMode() {
        assertEquals("japan unchanged japan>tokyo", decide("rule", "", "203.0.113.5:443"))
        assertEquals("direct unchanged direct", decide("rule", "", "203.0.113.5:22"))
        assertEquals("block unchanged block", decide("rule", "", "203.0.113.6:443", "ads.blocked.test"))
        assertEquals("streaming unchanged streaming>taiwan>taipei", decide("rule", "", "203.0.113.7:443", "www.video.test"))
        assertEquals("rescue unchanged rescue>osaka", decide("rule", "", "203.0.113.9:8443"))
    }

    @Test
    fun globalMode() {
        assertEquals("block block block", decide("global", "", "203.0.113.5:443"))
        assertEquals("taiwan group:taiwan taiwan>taipei", decide("global", "group:taiwan", "203.0.113.5:443"))
        assertEquals("direct unchanged direct", decide("global", "group:taiwan", "203.0.113.5:22"))
        assertEquals("block unchanged block", decide("global", "group:taiwan", "203.0.113.6:443", "ads.blocked.test"))
        assertEquals("osaka node:osaka osaka", decide("global", "node:osaka", "203.0.113.9:8443"))
        assertEquals("unknown-target", decide("global", "group:Proxy", "203.0.113.5:443"))
    }

    @Test
    fun directMode() {
        assertEquals("direct direct direct", decide("direct", "", "203.0.113.5:443"))
        assertEquals("direct direct direct", decide("direct", "", "203.0.113.7:443", "www.video.test"))
        assertEquals("block unchanged block", decide("direct", "", "203.0.113.6:443", "ads.blocked.test"))
    }
}
