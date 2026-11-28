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

    /** From [RvRoundButton] to the floating [RvPrimaryButton] beside it (§8, N06). */
    val ctaGap = 8.dp
}
