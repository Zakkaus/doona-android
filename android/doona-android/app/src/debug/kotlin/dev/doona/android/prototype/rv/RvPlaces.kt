package dev.doona.android.prototype.rv

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import dev.doona.android.R

/**
 * A country the sample's nodes are in: its flag, its centroid in degrees and its [label]. Region codes are the enum's
 * names; [names] are the city and country names a node's name may carry instead, lower case.
 */
internal enum class RvPlace(val lat: Float, val lon: Float, @DrawableRes val flag: Int, @StringRes val label: Int, val names: List<String>) {
    HK(22.35f, 114.15f, R.drawable.flag_hk, R.string.rv_region_hk, listOf("hong kong", "香港")),
    JP(36.2f, 138.25f, R.drawable.flag_jp, R.string.rv_region_jp, listOf("japan", "tokyo", "osaka", "日本", "東京", "大阪")),
    KR(35.9f, 127.8f, R.drawable.flag_kr, R.string.rv_region_kr, listOf("korea", "seoul", "韓國", "首爾")),
    SG(1.35f, 103.82f, R.drawable.flag_sg, R.string.rv_region_sg, listOf("singapore", "新加坡")),
    TW(23.7f, 120.96f, R.drawable.flag_tw, R.string.rv_region_tw, listOf("taiwan", "taipei", "台灣", "臺灣", "台北")),
    US(39.83f, -98.58f, R.drawable.flag_us, R.string.rv_region_us, listOf("united states", "los angeles", "san jose", "美國")),
}

/**
 * The country a node's name points at, or null: a flag emoji first (`🇯🇵 Osaka`), then a city or country name
 * (`Tokyo 01`), then a region code standing as a word of its own (`hk-01`, `JP 02`).
 */
internal fun placeOf(name: String): RvPlace? {
    val flag = FLAG.find(name)?.value?.codePoints()?.toArray()?.joinToString("") { Char(it - REGIONAL_A + 'A'.code).toString() }
    val lower = name.lowercase()
    val words = lower.split(NOT_A_LETTER).toSet()
    return RvPlace.entries.firstOrNull { it.name == flag }
        ?: RvPlace.entries.firstOrNull { place -> place.names.any { it in lower } }
        ?: RvPlace.entries.firstOrNull { it.name.lowercase() in words }
}

/** [name] without its flag emoji, which the flag disc shows instead. */
internal fun withoutFlag(name: String): String = name.replace(FLAG, "").trim()

/** Two regional indicator symbols: a flag emoji. */
private val FLAG = Regex("[\\x{1F1E6}-\\x{1F1FF}]{2}")
private val NOT_A_LETTER = Regex("[^\\p{L}]+")
private const val REGIONAL_A = 0x1F1E6
