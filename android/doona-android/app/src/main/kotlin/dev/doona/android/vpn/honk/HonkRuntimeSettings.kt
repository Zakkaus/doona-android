package dev.doona.android.vpn.honk

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * honk's `RuntimeSettings` (`GET /api/v1/runtime/settings`), the part the app reads: [geodata] is there when honk's
 * geodata sources are configurable. Fields the app has no use for are skipped.
 */
@Serializable
internal class RuntimeSettings(val geodata: GeoDataSettings? = null)

/** honk's `GeoDataSettings`, the part the app reads. */
@Serializable
internal class GeoDataSettings(@SerialName("auto_update") val autoUpdate: GeoDataAutoUpdate)

/** honk's `GeoDataAutoUpdate`: whether honk updates on a schedule, and every how many hours. */
@Serializable
internal class GeoDataAutoUpdate(val enabled: Boolean, @SerialName("interval_hours") val intervalHours: Int)
