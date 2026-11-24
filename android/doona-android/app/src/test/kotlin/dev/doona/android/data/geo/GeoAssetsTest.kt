package dev.doona.android.data.geo

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.InputStream

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
            val stage = assets.prepare { active }
            assertEquals("active=$active", offer, stage != null)
            if (stage != null) {
                assertEquals(folder.root, stage.parentFile)
                assertEquals(bundled.mapKeys { it.key.substringAfter('/') }, stage.listFiles()!!.associate { it.name to it.readText() })
                assets.discard(stage)
                assertFalse(stage.exists())
            }
        }
    }

    @Test
    fun sweepsAStageLeftByAKillEvenWhenItDoesNotOfferTheApk() = runBlocking {
        val stale = folder.newFolder("geo-apk-stage")
        stale.resolve("geoip.dat").writeText("partial copy")
        val assets = GeoAssets({ folder.root }) { bundled.getValue(it).byteInputStream() }
        assertNull(assets.prepare { "2026-11-23" })
        assertFalse(stale.exists())
    }

    @Test
    fun cancellationDuringAChunkDoesNotReadAnotherChunkOrLeaveAStage() = runBlocking {
        lateinit var copy: Job
        var reads = 0
        var cancelled = false
        val assets = GeoAssets({ folder.root }) { path ->
            if (path == "geo/pins.json") {
                bundled.getValue(path).byteInputStream()
            } else {
                object : InputStream() {
                    override fun read(): Int = error("expected bulk reads")

                    override fun read(bytes: ByteArray): Int {
                        reads++
                        copy.cancel()
                        return if (reads == 1) 1 else -1
                    }
                }
            }
        }
        copy = launch {
            try {
                assets.prepare { null }
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            }
        }
        copy.join()
        assertTrue(cancelled)
        assertEquals(1, reads)
        assertTrue(folder.root.listFiles()!!.isEmpty())
    }
}
