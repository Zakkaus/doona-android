package dev.doona.android.designsystem.rv

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import dev.doona.android.R

/** What a recognised emoji in a name is drawn as: a country's flag, or one of the library's brand or group marks. */
internal sealed interface RvNameMark {
    /** The flag of the ISO 3166 alpha-2 [code]. */
    data class Flag(val code: String) : RvNameMark

    /** A brand or group glyph, named [name] where the name is left with nothing else. */
    data class Icon(@DrawableRes val glyph: Int, @StringRes val name: Int) : RvNameMark
}

/** A name as the rows show it: the [mark] its recognised emoji became (the first one), and what is left of the [text]. */
internal data class RvShownName(val mark: RvNameMark?, val text: String)

/**
 * The emoji of a node's or group's name that are ours (emoji1): flag emoji become [RvNameMark.Flag] and the few that
 * name a brand or a group become [RvNameMark.Icon]; recognised emoji leave the displayed name, an unrecognised one
 * stays in it. Only what is drawn changes: the raw name is what search, copying, the node's detail and the profile keep.
 */
internal fun rvShownName(raw: String): RvShownName {
    var mark: RvNameMark? = null
    val kept = StringBuilder()
    var i = 0
    while (i < raw.length) {
        val cp = raw.codePointAt(i)
        val width = Character.charCount(cp)
        val flag = if (cp.isRegionalIndicator() && i + width < raw.length) raw.codePointAt(i + width).takeIf { it.isRegionalIndicator() } else null
        val icon = ICONS[cp]
        when {
            flag != null -> {
                mark = mark ?: RvNameMark.Flag("${cp.letter()}${flag.letter()}")
                i += width + Character.charCount(flag)
            }

            icon != null -> {
                mark = mark ?: icon
                i += width
                if (i < raw.length && raw[i] == VARIATION_SELECTOR) i++
            }

            else -> {
                kept.appendCodePoint(cp)
                i += width
            }
        }
    }
    return RvShownName(mark, kept.toString().replace(SPACES, " ").trim())
}

private fun Int.isRegionalIndicator() = this in REGIONAL_A..REGIONAL_Z

private fun Int.letter() = ('A' + (this - REGIONAL_A))

private const val REGIONAL_A = 0x1F1E6
private const val REGIONAL_Z = 0x1F1FF
private const val VARIATION_SELECTOR = '️'
private val SPACES = Regex("\\s+")

/** 🍎 Apple, 📺 YouTube, 🎮 games, 🚀 the selector group: the marks the library has. Games has no glyph of its own yet. */
private val ICONS: Map<Int, RvNameMark.Icon> = mapOf(
    0x1F34E to RvNameMark.Icon(R.drawable.rv_brand_apple, R.string.rv_name_apple),
    0x1F4FA to RvNameMark.Icon(R.drawable.rv_brand_youtube, R.string.rv_name_youtube),
    0x1F3AE to RvNameMark.Icon(R.drawable.rv_sym_apps, R.string.rv_service_games),
    0x1F680 to RvNameMark.Icon(R.drawable.rv_sym_touch_app, R.string.rv_name_node_select),
)
