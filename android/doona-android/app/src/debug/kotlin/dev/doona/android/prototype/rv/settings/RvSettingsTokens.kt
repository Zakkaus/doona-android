package dev.doona.android.prototype.rv.settings

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.doona.android.prototype.rv.RvInter

// Raw values of the settings sample, from revolut-android-measured.md §13 (Pixel, 1 dp = 2.55 px, so they hold at any
// screen width). Only the components of this folder read them.

internal object RvSettingsSpace {
    /** Side margin of cards, section titles and the large title. */
    val margin = 16.dp
    val cardGap = 16.dp

    /** Start and end padding of a row, and from its glyph to its text. */
    val rowInset = 16.dp
    val glyphGap = 16.dp
    val trailingGap = 12.dp

    val bottom = 24.dp

    /** The hero's two round actions; the first section title sits its usual 24 dp under them, as under any content. */
    val actionColumn = 96.dp
    val actionGap = 8.dp

    /** How far under the bar the hero's dot map is centred, so East Asia's land falls behind the round actions. */
    val mapDrop = 16.dp

    /** The theme page's colour row (48 dp targets 10 dp in, so a 36 dp disc's edge sits 16 dp in), its preview and its mode columns. */
    val accentPad = 8.dp
    val accentInset = 10.dp
    val previewPad = 16.dp
    val modePad = 16.dp
    val modeGap = 8.dp
}

internal object RvSettingsSize {
    /** The text line of a title; glyphs and trailing controls centre on it, so a wrapped row keeps them on its first line. */
    val titleLine = 26.dp
    val glyph = 24.dp

    /** A colour disc, and the ring round the selected one. */
    val accentDisc = 36.dp
    val accentRing = 44.dp
    val accentRingStroke = 2.dp

    /**
     * The preview: the 活動 page laid out on a phone's [previewScreen] under a [previewStatus] status bar, its top
     * [previewHeight] shown scaled down in a rounded panel; and a mode's picture.
     */
    val previewScreen = DpSize(412.dp, 915.dp)
    val previewStatus = 24.dp

    // It ends in the gap between the round actions and the first card, so no sliver of the card shows (L-15).
    val previewHeight = 436.dp
    val modeThumb = DpSize(64.dp, 96.dp)
}

internal object RvSettingsAlpha {
    /** The hero's dot texture: a settings page asks for less than the activity header. */
    const val HERO_DOTS = 0.5f

    /** A control that does not apply while another setting overrides it. */
    const val DISABLED = 0.38f
}

/** The five accent colours, each a primary in light; the dark theme's is the same hue lifted toward white. */
internal object RvAccentColors {
    val blue = Color(0xFF366AD1)
    val purple = Color(0xFF7A4FD3)
    val orange = Color(0xFFD9680B)
    val pink = Color(0xFFD6336C)

    /** How far the dark primary and the light container move toward white. */
    const val DARK_LIFT = 0.45f
    const val CONTAINER_LIGHT = 0.85f
    const val CONTAINER_DARK = 0.7f
    val onPrimaryDark = Color(0xFF1B1B1F)
}
