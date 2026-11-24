package dev.doona.android.data.geo

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class GeoAssetsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val bundled = mapOf(
        "geo/pins.json" to """{"release_date":"2026-11-23","version":1}""",
        "geo/geoip.dat" to "ip bytes",
        "geo/geosite.dat" to "site bytes",
    )

    @Test
    fun offersOnlyWhenTheApkIsNewerOrNoGenerationIsActive() = runBlocking {
        val assets = GeoAssets({ folder.root }) { bundled.getValue(it).byteInputStream() }
        val decisions = mapOf(null to true, "2026-11-22" to true, "2026-11-23" to false, "2026-11-24" to false)
        for ((active, offer) in decisions) {
            val path = assets.withBundle({ active }) { path ->
                assertEquals("active=$active", offer, path != null)
                if (path != null) {
                    val stage = File(path)
                    assertEquals(folder.root, stage.parentFile)
                    assertEquals(bundled.mapKeys { it.key.substringAfter('/') }, stage.listFiles()!!.associate { it.name to it.readText() })
                }
                path
            }
            if (path != null) assertFalse(File(path).exists())
        }
    }

    @Test
    fun sweepsAStageLeftByAKillEvenWhenItDoesNotOfferTheApk() = runBlocking {
        val stale = folder.newFolder("geo-apk-stage")
        stale.resolve("geoip.dat").writeText("partial copy")
        val assets = GeoAssets({ folder.root }) { bundled.getValue(it).byteInputStream() }
        assets.withBundle({ "2026-11-23" }) { path ->
            assertNull(path)
            assertFalse(stale.exists())
        }
    }

    @Test
    fun aCopyFailureRemovesThePartialStageWithoutCallingStart() = runBlocking {
        val failure = IOException("asset read failed")
        val assets = GeoAssets({ folder.root }) { path ->
            if (path == "geo/geosite.dat") throw failure
            bundled.getValue(path).byteInputStream()
        }
        val result = runCatching { assets.withBundle<Unit>({ null }) { error("start called with an incomplete bundle") } }
        val thrown = result.exceptionOrNull()
        assertTrue(thrown is IOException)
        assertEquals(failure.message, thrown?.message)
        assertTrue(folder.root.listFiles()!!.isEmpty())
    }
}
