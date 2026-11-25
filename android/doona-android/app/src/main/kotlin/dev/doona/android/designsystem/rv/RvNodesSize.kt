package dev.doona.android.designsystem.rv

import androidx.compose.ui.unit.dp

/** Sizes of the 節點 page and the node detail page, after revolut-android-measured.md (its section in brackets). */
internal object RvNodesSize {
    /** The 'Top movers' grid's flags, and the list rows' (§7: 40 dp logo circles). */
    val topFlag = 54.dp
    val rowFlag = 40.dp

    /** 'Top movers': flag to name, and between the grid's two lines. */
    val topLabelGap = 8.dp
    val topRowGap = 16.dp

    /** The ▲▼ before a change (§7: a filled 8 dp triangle in the number's colour). */
    val delta = 8.dp

    /**
     * The round companion beside the floating [RvPrimaryButton] (§8, N06: a 56 dp disc
     * 8 dp from the 52 dp pill, on one centre line).
     */
    val ctaCompanion = 56.dp
    val ctaGap = 8.dp
    val ctaGlyph = 24.dp
}
