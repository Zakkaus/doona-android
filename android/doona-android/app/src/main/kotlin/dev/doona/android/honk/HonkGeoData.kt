package dev.doona.android.honk

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * honk's `GeoData` (`GET /api/v1/geodata`, and the result of a `geodata_update`): the files the running datapath was
 * built from, the last update attempt, and the categories the active configuration needs from each file
 * ([requiredCodes], by asset kind: `geosite`, `geoip`). Only the fields the app reads; the others are skipped.
 */
@Serializable
internal class GeoData(
    val assets: List<GeoAsset>,
    @SerialName("last_checked_at") val lastCheckedAt: String? = null,
    @SerialName("last_updated_at") val lastUpdatedAt: String? = null,
    @SerialName("last_error") val lastError: SafeError? = null,
    @SerialName("required_codes") val requiredCodes: Map<String, List<String>> = emptyMap(),
)

/** honk's `GeoAsset`: one loaded file of [kind] (`geosite`, `geoip`); [modifiedAt] is null when the filesystem reports no time. */
@Serializable
internal class GeoAsset(val kind: String, val sha256: String, @SerialName("modified_at") val modifiedAt: String? = null)

/**
 * The geodata honk loaded. Never touches the network. honk without geodata (`resources.geodata.available` false)
 * answers 404 `capability_not_supported`. Blocking: run it on `Dispatchers.IO`.
 */
internal fun NativeApi.geoData(): GeoData = read("GET", "/api/v1/geodata", GeoData.serializer())
