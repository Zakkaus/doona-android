package dev.doona.android.honk

import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.feature.model.RvGeoInterval
import dev.doona.android.feature.model.RvGeoUpdate
import dev.doona.android.feature.settings.RvLogLevel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import kotlin.time.Duration.Companion.seconds

/** Response bodies are the examples of honk's native API contract (`native_api_openapi.yaml`), as in [SettingsHonk]. */
class NativeSettingsTest {
    private val api = SettingsHonk()
    private val utc = NativeSettings(api) { ZoneId.of("UTC") }

    @Test
    fun theDateIsTheNewestLoadedFilesDayInThePhonesZone() {
        api.on("GET", "/api/v1/geodata", 200, SettingsHonk.geoData(modified = "2026-08-14T23:30:00Z"))

        assertEquals(LocalDate.of(2026, 8, 14), utc.geoData().date)
        assertEquals(LocalDate.of(2026, 8, 15), NativeSettings(api) { ZoneId.of("Australia/Melbourne") }.geoData().date)
        assertEquals(RvGeoUpdate.Idle, utc.geoData().update)
    }

    @Test
    fun honksLastFailedAttemptShowsAsAFailure() {
        api.on("GET", "/api/v1/geodata", 200, SettingsHonk.geoData(error = SettingsHonk.GEODATA_ERROR))

        assertEquals(RvGeoUpdate.Failed, utc.geoData().update)
    }

    @Test
    fun anUpdateWaitsForAnAttemptAfterItsOwnAndSeesTheReplacedFile() = runTest {
        api.on("GET", "/api/v1/geodata", 200, SettingsHonk.GEODATA)
            .on("GET", "/api/v1/geodata", 200, SettingsHonk.GEODATA)
            .on("GET", "/api/v1/geodata", 200, SettingsHonk.geoData(checked = "2026-08-15T10:00:09Z", updated = "2026-08-15T10:00:08Z", modified = "2026-08-15T10:00:08Z"))
            .on("POST", "/api/v1/geodata/update", 202, SettingsHonk.GEODATA_QUEUED)

        val row = utc.updateGeo(1.seconds)

        assertEquals(RvGeoUpdate.Updated, row.update)
        assertEquals(LocalDate.of(2026, 8, 15), row.date)
        assertEquals(listOf("GET", "POST", "GET", "GET"), api.calls.map { it.method })
        assertNull(api.calls[1].body)
    }

    @Test
    fun theSameBytesAreLatestAndAFailedAttemptIsAFailure() = runTest {
        api.on("GET", "/api/v1/geodata", 200, SettingsHonk.GEODATA)
            .on("GET", "/api/v1/geodata", 200, SettingsHonk.geoData(checked = "2026-08-15T10:00:09Z"))
            .on("POST", "/api/v1/geodata/update", 202, SettingsHonk.GEODATA_QUEUED)
        assertEquals(RvGeoUpdate.Latest, utc.updateGeo(1.seconds).update)

        val failing = SettingsHonk()
            .on("GET", "/api/v1/geodata", 200, SettingsHonk.GEODATA)
            .on("GET", "/api/v1/geodata", 200, SettingsHonk.geoData(checked = "2026-08-15T10:00:09Z", error = SettingsHonk.GEODATA_ERROR))
            .on("POST", "/api/v1/geodata/update", 202, SettingsHonk.GEODATA_QUEUED)
        assertEquals(RvGeoUpdate.Failed, NativeSettings(failing).updateGeo(1.seconds).update)
    }

    @Test
    fun anUpdateAlreadyRunningIsFollowedAndARefusalThrows() = runTest {
        api.on("GET", "/api/v1/geodata", 200, SettingsHonk.GEODATA)
            .on("GET", "/api/v1/geodata", 200, SettingsHonk.geoData(checked = "2026-08-15T10:00:09Z", updated = "2026-08-15T10:00:08Z"))
            .on("POST", "/api/v1/geodata/update", 409, SettingsHonk.GEODATA_IN_FLIGHT)
        assertEquals(RvGeoUpdate.Updated, utc.updateGeo(1.seconds).update)

        val refusing = SettingsHonk()
            .on("GET", "/api/v1/geodata", 200, SettingsHonk.GEODATA)
            .on("POST", "/api/v1/geodata/update", 503, "")
        val thrown = runCatching { NativeSettings(refusing).updateGeo(1.seconds) }.exceptionOrNull()
        assertTrue(thrown is IllegalStateException)
    }

    @Test
    fun theScheduleIsOffOrTheNearerPreset() {
        val cases = listOf(
            SettingsHonk.runtimeSettings(hours = 24) to RvGeoInterval.Daily,
            SettingsHonk.runtimeSettings(hours = 48) to RvGeoInterval.Daily,
            SettingsHonk.runtimeSettings(hours = 120) to RvGeoInterval.Weekly,
            SettingsHonk.runtimeSettings(hours = 168) to RvGeoInterval.Weekly,
            SettingsHonk.runtimeSettings(enabled = false, hours = 24) to RvGeoInterval.Off,
        )
        for ((body, interval) in cases) {
            val honk = SettingsHonk().on("GET", "/api/v1/runtime/settings", 200, body)
            assertEquals(body, interval, NativeSettings(honk).geoInterval())
        }
    }

    @Test
    fun noScheduleWhenHonkReportsNoGeodataSettings() {
        api.on("GET", "/api/v1/runtime/settings", 200, """{"observed_at":"2026-08-15T10:00:05Z","source":"runtime","log":{"level":"debug","buffered_records":4096}}""")

        assertNull(utc.geoInterval())
    }

    @Test
    fun eachChoicePatchesHonksAutomaticUpdate() {
        api.on("PATCH", "/api/v1/runtime/settings", 200, SettingsHonk.RUNTIME_SETTINGS)

        RvGeoInterval.entries.forEach(utc::setGeoInterval)

        assertEquals(
            listOf(
                """{"geodata":{"auto_update":{"enabled":false}}}""",
                """{"geodata":{"auto_update":{"enabled":true,"interval_hours":24}}}""",
                """{"geodata":{"auto_update":{"enabled":true,"interval_hours":168}}}""",
            ).map(Json::parseToJsonElement),
            api.calls.map { Json.parseToJsonElement(it.body!!) },
        )
    }

    @Test
    fun theLogLevelIsARuntimePatchAndARefusalThrows() {
        api.on("PATCH", "/api/v1/runtime/settings", 200, SettingsHonk.RUNTIME_SETTINGS)
            .on("PATCH", "/api/v1/runtime/settings", 422, "")

        utc.setLogLevel(RvLogLevel.Debug)

        assertEquals(Json.parseToJsonElement("""{"log":{"level":"debug"}}"""), Json.parseToJsonElement(api.calls.single().body!!))
        val thrown = assertThrows(EngineException::class.java) { utc.setLogLevel(RvLogLevel.Warn) }
        assertEquals(ErrorCode.InvalidArgument, thrown.code)
    }
}
