package dev.doona.android.vpn

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Where the core places a region or a city, in degrees (`dto/location.rs`); display hints read from a node's name. */
@Serializable
internal data class CoordinatesDto(val lat: Double, val lon: Double)

/** A city a node's name names: its id, its names when the core has them, and where it is. */
@Serializable
internal data class CityDto(val id: String, val names: CityNamesDto? = null, val lat: Double, val lon: Double)

@Serializable
internal data class CityNamesDto(
    val en: String,
    @SerialName("zh-TW") val zhTw: String,
    @SerialName("zh-CN") val zhCn: String,
)
