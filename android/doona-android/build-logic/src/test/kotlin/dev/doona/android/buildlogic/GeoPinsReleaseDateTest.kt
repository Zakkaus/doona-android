package dev.doona.android.buildlogic

import org.gradle.api.GradleException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDate

class GeoPinsReleaseDateTest {
    @get:Rule
    val dir = TemporaryFolder()

    private val files = listOf("geoip.dat", "geosite.dat").joinToString(",") {
        """"$it": {"source": "https://example.test/$it", "size_bytes": 1, "sha256": "${"0".repeat(64)}"}"""
    }

    /** A valid manifest except for [releaseDate], the raw JSON of `release_date`, or no such key when null. */
    private fun pins(releaseDate: String?): GeoPins {
        val date = releaseDate?.let { """"release_date": $it,""" }.orEmpty()
        val file = dir.newFile()
        file.writeText("""{"version": 1, $date "files": {$files}}""")
        return GeoPins.read(file)
    }

    @Test
    fun theReleaseDateIsRead() {
        assertEquals(LocalDate.of(2026, 11, 23), pins("\"2026-11-23\"").releaseDate)
    }

    @Test
    fun aMissingOrIllFormedReleaseDateFails() {
        for (date in listOf(null, "null", "20261123", "\"\"", "\"2026-13-01\"", "\"2026-02-30\"", "\"26-11-23\"", "\"2026/11/23\"")) {
            val message = assertThrows(GradleException::class.java) { pins(date) }.message.orEmpty()
            assertTrue("$date: $message", "release_date must be a yyyy-MM-dd date" in message)
        }
    }
}
