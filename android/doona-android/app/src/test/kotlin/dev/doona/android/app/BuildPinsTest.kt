package dev.doona.android.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/** The app's copies of what the core is built from agree with the files that pin it. */
class BuildPinsTest {
    // Gradle runs unit tests in the module's directory, android/doona-android/app.
    private val core = File("../../../crates/doona-core")

    @Test
    fun `the About page's honk revision is the one Cargo_toml pins`() {
        val revs = Regex("""^honk-(?:config|outbound) = \{ git = "https://github.com/daeuniverse/honk", rev = "([0-9a-f]{40})"""", RegexOption.MULTILINE)
            .findAll(core.resolve("Cargo.toml").readText()).map { it.groupValues[1] }.toSet()
        assertEquals("honk-config and honk-outbound share one revision", 1, revs.size)
        assertTrue(revs.single().startsWith(HONK_REVISION))
        assertEquals(7, HONK_REVISION.length)
    }

    @Test
    fun `the shipped GeoIP and GeoSite date is geo-pins_json's release_date`() {
        val pins = core.resolve("tools/geo-pins.json").readText()
        val date = Regex(""""release_date"\s*:\s*"([^"]+)"""").find(pins)!!.groupValues[1]
        assertEquals(date, AppSettingsStore.SHIPPED_GEO_DATE)
        LocalDate.parse(AppSettingsStore.SHIPPED_GEO_DATE)
    }
}
