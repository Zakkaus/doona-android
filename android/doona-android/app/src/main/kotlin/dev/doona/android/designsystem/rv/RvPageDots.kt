package dev.doona.android.designsystem.rv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * The pager's dots under the tiles: the current one larger and darker, the others in the control grey (3:1 on the page, S06).
 * Given [onPick], each dot is also a tab named by its [labels] entry, [RvSize.touch] high: a tap shows its page.
 */
@Composable
internal fun RvStatsDots(count: Int, current: Int, modifier: Modifier = Modifier, labels: List<String> = emptyList(), onPick: ((Int) -> Unit)? = null) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        repeat(count) { i ->
            val tab = if (onPick == null) {
                Modifier
            } else {
                Modifier.height(RvSize.touch).rvTabSelect(i == current) { onPick(i) }.semantics { contentDescription = labels[i] }
            }
            Box(tab.width(RvPageDotsSize.dotPitch), contentAlignment = Alignment.Center) {
                val size = if (i == current) RvPageDotsSize.dot else RvPageDotsSize.dotIdle
                Box(Modifier.size(size).clip(CircleShape).background(if (i == current) Rv.colors.textSecondary else Rv.colors.control))
            }
        }
    }
}

/** The page dots under 活動's hero views and 統計's cards: the current one larger and darker. */
internal object RvPageDotsSize {
    val dot = 8.dp
    val dotIdle = 5.dp
    val dotPitch = 12.dp
}
