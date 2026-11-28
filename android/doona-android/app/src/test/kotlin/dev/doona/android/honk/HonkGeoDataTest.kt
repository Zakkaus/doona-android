package dev.doona.android.honk

import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** The bodies are the `getGeoData` examples of honk's contract (`honk/geodata/loaded`, `packaged`) and the RV sample. */
class HonkGeoDataTest {
    private val honk = FakeHonk()

    @Test
    fun theLoadedFilesAndTheLastAttemptDecode() {
        honk.on("GET", "/api/v1/geodata", 200, HonkFixtures.body("geodata/loaded"))

        val data = honk.geoData()

        assertEquals(listOf("geosite", "geoip"), data.assets.map { it.kind })
        assertEquals("2026-08-14T03:00:00Z", data.assets.first().modifiedAt)
        assertEquals("2026-08-15T03:12:40Z", data.lastCheckedAt)
        assertEquals("2026-08-14T03:00:00Z", data.lastUpdatedAt)
        assertNull(data.lastError)
        assertEquals(listOf("category-ads-all", "cn", "geolocation-!cn", "private"), data.requiredCodes["geosite"])
        assertEquals(listOf("cn", "private"), data.requiredCodes["geoip"])
    }

    @Test
    fun aFailedAttemptCarriesItsSafeError() {
        honk.on("GET", "/api/v1/geodata", 200, HonkFixtures.body("geodata/packaged"))

        val error = checkNotNull(honk.geoData().lastError)

        assertEquals("download_failed", error.code)
    }

    @Test
    fun theSampleIsTheCopyTheApkShipsNeverUpdated() {
        honk.on("GET", "/api/v1/geodata", 200, HonkFixtures.body("geodata/rv-sample"))

        val data = honk.geoData()

        assertEquals(listOf("2026-11-20T12:00:00Z", "2026-11-20T12:00:00Z"), data.assets.map { it.modifiedAt })
        assertNull(data.lastCheckedAt)
        assertNull(data.lastUpdatedAt)
    }

    @Test
    fun honkWithoutGeodataReportsTheUnsupportedCapability() {
        honk.on("GET", "/api/v1/geodata", 404, """{"error":{"code":"capability_not_supported","message":"Unavailable"},"request_id":"geo"}""")

        assertEquals(ErrorCode.Unsupported, assertThrows(EngineException::class.java) { honk.geoData() }.code)
    }
}
