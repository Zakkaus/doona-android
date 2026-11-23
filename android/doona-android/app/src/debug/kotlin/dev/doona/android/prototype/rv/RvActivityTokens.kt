package dev.doona.android.prototype.rv

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 活動's own values, as the owner approved them at 609336cb ("609336cb 是對的", 2026-11-23). Audit and merge rounds moved
 * 活動 to the shared values in RvTokens.kt without the owner's yes; these bring it back while 歡迎, 設定, 連線詳情, 流量 and
 * 節點 keep the shared ones. Not design-system roles: the owner rules on them later.
 */
internal object RvActivityTokens {
    /** From the status bar to the hero's caption (shared: [RvSpace.headerTop]). */
    val headerTop = 72.dp

    /** The node's place on the map, this far under the status bar: under the top bar's glass (shared: [RvDots.focusTop]). */
    val focusTop = 40.dp

    /** The round actions: the disc, the gap to its label, and from the labels to the first card. */
    val actionDisc = 52.dp
    val discToLabel = 10.dp
    val actionsToCard = 32.dp

    /** The 全域 outbound card's rows: padding, disc and glyph, the gap to the text, the height with a subtitle. */
    val rowPadding = 18.dp
    val rowDisc = 44.dp
    val rowGlyph = 20.dp
    val rowGap = 18.dp
    val rowTwoLine = 84.dp

    /** The hero: the caption, the figure whole with its unit smaller, and both rates under it. */
    val caption = RvTypeStep(16, 22, FontWeight.Normal)
    val hero = RvTypeStep(42, 48, FontWeight.Bold, display = true)
    val heroUnit = RvTypeStep(24, 32, FontWeight.SemiBold, display = true)
    val sub = RvTypeStep(15, 20, FontWeight.Medium)

    /** The round actions' labels, and the 全域 outbound card's row title over its subtitle. */
    val action = RvTypeStep(15, 20, FontWeight.Medium)
    val rowTitle = RvTypeStep(17, 24, FontWeight.Medium)
    val rowSub = RvTypeStep(15, 20, FontWeight.Normal)

    /** 今日用量's title (shared: [RvTypeScale.cardTitle]). */
    val cardTitle = RvTypeStep(15, 20, FontWeight.SemiBold)

    /** 今日用量's chart: its grid in the text colour, and both bands in their lines' colours, at these alphas. */
    const val GRID_LIGHT = 0.06f
    const val GRID_DARK = 0.025f
    const val BAND_LIGHT = 0.16f
    const val BAND_DARK = 0.22f
}
