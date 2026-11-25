package dev.doona.android.designsystem.rv

import androidx.compose.ui.unit.dp

/** The 節點 detail page's latency dots (RvLatencyDots): plot metrics, after revolut-android-measured.md §10.2. */
internal object RvLatencyDotsSize {
    /** The plot, room above for the top label, and the gap from the plot to the label strip. */
    val plot = 140.dp
    val head = 10.dp
    val plotGap = 8.dp

    /** The dots (§10.2: Revolut's EPS dots are 8 dp, N04) and a timed-out test's ring. */
    val dot = 8.dp
    val ring = 1.5.dp
}
