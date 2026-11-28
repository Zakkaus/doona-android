package dev.doona.android.designsystem.rv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier

/**
 * One chip of [RvFilterChipRow]: its [label], and what a tap does. With [items] a tap opens that menu under the chip and
 * [onClick] is not used; with none it runs [onClick].
 */
@Immutable
internal class RvChipPick(val label: String, val onClick: () -> Unit = {}, val items: List<RvMenuItem> = emptyList())

/**
 * Analytics' two chips under a title or a search: the subscription at the start, the order at the end. The 節點 page and
 * a policy group's page list nodes the same way and share this row. When the controls do not fit together, the end chip
 * moves to the next line instead of being cut off.
 */
@Composable
internal fun RvFilterChipRow(start: RvChipPick, end: RvChipPick, modifier: Modifier = Modifier) {
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(RvChrome.buttonGap)) {
        Chip(start)
        Chip(end)
    }
}

@Composable
private fun Chip(pick: RvChipPick) {
    RvMenuAnchor(pick.items) { open, actions -> RvFilterChip(pick.label, open ?: pick.onClick, actions) }
}
