package dev.doona.android.engine

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** BuildInfo carries what the repository pins, read here from the same files independently of the generator. */
class BuildInfoTest {
    private val core: File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .map { it.resolve("crates/doona-core") }
        .first { it.isDirectory }

    @Test
    fun honkRevisionIsTheCoresPin() {
        val pinned = Regex("""honk-config = \{ git = "https://github.com/daeuniverse/honk", rev = "([0-9a-f]{40})"""")
            .find(core.resolve("Cargo.toml").readText())!!.groupValues[1]
        assertEquals(pinned, BuildInfo.HONK_REVISION)
    }

    @Test
    fun geoShippedDateIsThePinsReleaseDate() {
        val released = Regex(""""release_date"\s*:\s*"([0-9-]+)"""")
            .find(core.resolve("tools/geo-pins.json").readText())!!.groupValues[1]
        assertEquals(released, BuildInfo.GEO_SHIPPED_DATE)
    }
}
