package dev.doona.android.prototype.rv.settings

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.doona.android.prototype.rv.RvInter
import dev.doona.android.prototype.rv.RvInterDisplay

// Raw values of the settings sample, from revolut-android-measured.md §13 (Pixel, 1 dp = 2.55 px, so they hold at any
// screen width). Only the components of this folder read them.

internal object RvSettingsSpace {
    /** Side margin of cards, section titles and the large title. */
    val margin = 16.dp
    val cardGap = 16.dp

    /** A section title: 24 above it, 12 between it and its card. */
    val sectionAbove = 24.dp
    val sectionBelow = 12.dp

    /** A group footer sits this far under its card, indented as a row is, so its text starts 32 dp from the screen's edge. */
    val footerAbove = 8.dp
    val footerInset = 16.dp

    /** Start and end padding of a row, and from its glyph to its text. */
    val rowInset = 16.dp
    val glyphGap = 16.dp
    val trailingGap = 12.dp
    val valueGap = 4.dp

    /** A row's top and bottom padding: with the 26 dp title line this makes the measured 58 dp, and 82 dp with a subtitle. */
    val rowPad = 16.dp
    val bottom = 24.dp

    /** The hero: the title row under the bar, then the two round actions. */
    val heroTop = 8.dp
    val markGap = 8.dp
    val titleToActions = 24.dp
    val discToLabel = 8.dp
    val actionColumn = 96.dp
    val actionGap = 8.dp
    val heroBottom = 24.dp

    /** How far under the bar the hero's dot map is centred, so East Asia's land falls behind the title. */
    val mapDrop = 16.dp

    /** The large title of a sub-page, from the bar to the first card. */
    val titleTop = 8.dp
    val titleBottom = 16.dp

    /** The theme page's colour row and preview. */
    val accentPad = 14.dp
    val previewPad = 12.dp

    /** Inside the preview: the caption to the figure, the figure to the actions, and between the actions. */
    val previewCaptionGap = 4.dp
    val previewGap = 16.dp
    val previewActionGap = 24.dp
}

internal object RvSettingsSize {
    val cardRadius = 20.dp
    val rowOneLine = 58.dp
    val rowTwoLine = 82.dp

    /** The text line of a title; glyphs and trailing controls centre on it, so a wrapped row keeps them on its first line. */
    val titleLine = 26.dp
    val glyph = 24.dp
    val chevron = 24.dp

    /** The bar over the scrolling page, under the status bar. */
    val bar = 56.dp
    val backDisc = 44.dp
    val backGlyph = 24.dp

    /** The hero: the duck mark beside the title, and a round action's disc and glyph. */
    val mark = 32.dp
    val actionDisc = 44.dp
    val actionGlyph = 24.dp

    /** The switch's node, its visible track, and its thumb, which is taller than the track. */
    val switchWidth = 50.dp
    val switchHeight = 27.dp
    val switchTrackWidth = 34.dp
    val switchTrackHeight = 17.dp
    val switchThumb = 19.dp
    val switchThumbShadow = 1.dp

    val radio = 24.dp
    val radioStroke = 2.dp
    val radioDot = 12.dp

    /** A colour disc, and the ring round the selected one. */
    val accentDisc = 36.dp
    val accentRing = 44.dp
    val accentRingStroke = 2.dp

    /** The preview: a mini 活動 hero in a rounded panel inside a card. */
    val previewRadius = 12.dp
    val previewHeight = 176.dp
    val previewMarkerTop = 28.dp
    val previewCaptionTop = 44.dp
    val previewDisc = 36.dp
    val previewGlyph = 20.dp
}

internal object RvSettingsAlpha {
    /** The page's colour over the scrolling content once the large title has collapsed. */
    const val STRIP = 0.98f

    /** The switch's track while on: the thumb's colour at half strength, as Revolut's #9bb5ea under #366ad1. */
    const val SWITCH_TRACK = 0.5f

    /** The collapse runs over this much scrolling, in dp. */
    const val COLLAPSE_DP = 32f

    /** The hero's dot texture: a settings page asks for less than the activity header. */
    const val HERO_DOTS = 0.5f

    /** A control that does not apply while another setting overrides it. */
    const val DISABLED = 0.38f
}

internal object RvSettingsType {
    /** The large title: 32 sp bold. It collapses to [compactTitle] on the bar. */
    val title = TextStyle(fontFamily = RvInterDisplay, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 38.sp)
    val compactTitle = TextStyle(fontFamily = RvInterDisplay, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp)
    val section = compactTitle
    val rowTitle = TextStyle(fontFamily = RvInter, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 26.sp)
    val rowSub = TextStyle(fontFamily = RvInter, fontWeight = FontWeight.Normal, fontSize = 17.sp, lineHeight = 24.sp)

    /** A trailing value: a step under the subtitle, so a long one still fits beside its title. */
    val value = TextStyle(fontFamily = RvInter, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 20.sp)
    val footer = TextStyle(fontFamily = RvInter, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp)
    val action = TextStyle(fontFamily = RvInter, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 24.sp)
    val previewCaption = footer
}

/** The five accent colours, each a primary in light; the dark theme's is the same hue lifted toward white. */
internal object RvAccentColors {
    val blue = Color(0xFF366AD1)
    val teal = Color(0xFF0E8A7D)
    val purple = Color(0xFF7A4FD3)
    val orange = Color(0xFFD9680B)
    val pink = Color(0xFFD6336C)

    /** How far the dark primary and the light container move toward white. */
    const val DARK_LIFT = 0.45f
    const val CONTAINER_LIGHT = 0.85f
    const val CONTAINER_DARK = 0.7f
    val onPrimaryDark = Color(0xFF1B1B1F)
}
