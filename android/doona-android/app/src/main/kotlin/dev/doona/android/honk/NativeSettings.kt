package dev.doona.android.honk

import dev.doona.android.feature.model.RvGeoData
import dev.doona.android.feature.model.RvGeoInterval
import dev.doona.android.feature.model.RvGeoUpdate
import dev.doona.android.feature.settings.RvLogLevel
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.time.Duration

/**
 * honk's GeoIP/GeoSite data and its runtime settings through the native API, decoded into honk's own types
 * ([dev.doona.android.honk]) and mapped to the pages' models: the loaded data and its update
 * (`/geodata`), the automatic update's schedule and the log level (`/runtime/settings`). honk downloads, verifies and
 * schedules the data itself; this class only reads and asks. Every call blocks in [NativeApi]: run them on
 * `Dispatchers.IO`.
 */
internal class NativeSettings(val api: NativeApi, private val zone: () -> ZoneId = ZoneId::systemDefault) {
    fun revision(): Long? = NativeRevisions(api).revisions().active

    /** The loaded data's date, null when honk reports none, and [RvGeoUpdate.Failed] while honk's last attempt failed. */
    fun geoData(): RvGeoData = api.geoData().let { it.toRv(if (it.lastError != null) RvGeoUpdate.Failed else RvGeoUpdate.Idle) }

    /**
     * Asks honk to update the data and waits, reading every [poll], until honk reports an attempt finished after this
     * one was asked: failed, a replaced file ([RvGeoUpdate.Updated]) or the same bytes ([RvGeoUpdate.Latest]). An
     * update honk is already running (409) is followed the same way; any other refusal throws.
     */
    suspend fun updateGeo(poll: Duration): RvGeoData {
        val before = api.geoData()
        val status = api.call("POST", "/api/v1/geodata/update", null).status
        check(status == 202 || status == 409) { "geodata update refused with $status" }
        while (true) {
            delay(poll)
            val now = api.geoData()
            if (now.lastCheckedAt == before.lastCheckedAt) continue
            return now.toRv(
                when {
                    now.lastError != null -> RvGeoUpdate.Failed
                    now.lastUpdatedAt != before.lastUpdatedAt -> RvGeoUpdate.Updated
                    else -> RvGeoUpdate.Latest
                },
            )
        }
    }

    /**
     * honk's automatic update as 自動更新's choice: off; 每天 for honk's default cron or 24 hours; 每週 for 168 hours;
     * 自訂 for any other cron or interval. Null when honk reports no schedule.
     */
    fun geoInterval(): RvGeoInterval? {
        val auto = api.read("GET", SETTINGS, RuntimeSettings.serializer()).geodata?.autoUpdate ?: return null
        return when {
            !auto.enabled -> RvGeoInterval.Off
            auto.cron == "0 3 * * *" -> RvGeoInterval.Daily
            auto.cron != null -> RvGeoInterval.Custom
            auto.intervalHours == DAY_HOURS -> RvGeoInterval.Daily
            auto.intervalHours == WEEK_HOURS -> RvGeoInterval.Weekly
            else -> RvGeoInterval.Custom
        }
    }

    /** Sets honk's automatic update; honk keeps it as an override of the configuration file's value. */
    fun setGeoInterval(interval: RvGeoInterval) = patch(
        buildJsonObject {
            putJsonObject("geodata") {
                putJsonObject("auto_update") {
                    put("enabled", interval != RvGeoInterval.Off)
                    when (interval) {
                        RvGeoInterval.Off -> Unit
                        RvGeoInterval.Custom, RvGeoInterval.Unavailable -> error("Not a selectable interval")
                        RvGeoInterval.Daily -> put("interval_hours", DAY_HOURS)
                        RvGeoInterval.Weekly -> put("interval_hours", WEEK_HOURS)
                    }
                }
            }
        },
    )

    /** Sets the level honk logs at until it restarts or activates a configuration; the configuration file keeps its own. */
    fun setLogLevel(level: RvLogLevel) = patch(buildJsonObject { putJsonObject("log") { put("level", level.name.lowercase()) } })

    private fun patch(body: JsonObject) {
        api.expect("PATCH", SETTINGS, body.toString())
    }

    /** The newest loaded file's day in the phone's zone, as the row shows a calendar day. */
    private fun GeoData.toRv(update: RvGeoUpdate) = RvGeoData(
        date = assets.mapNotNull { it.modifiedAt?.let(OffsetDateTime::parse) }.maxOrNull()?.atZoneSameInstant(zone())?.toLocalDate(),
        update = update,
    )

    private companion object {
        const val SETTINGS = "/api/v1/runtime/settings"
        const val DAY_HOURS = 24
        const val WEEK_HOURS = 7 * DAY_HOURS
    }
}
