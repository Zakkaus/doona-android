package dev.doona.android.designsystem.rv

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/** One chip of [RvFilterChipRow]. With no menu items, the chip is disabled. */
@Immutable
internal class RvChipPick(val label: String, val items: List<RvMenuItem> = emptyList())

/**
 * Analytics' two chips under a title or a search: the subscription at the start, the order at the end. The 節點 page and
 * a policy group's page list nodes the same way and share this row.
 */
@Composable
internal fun RvFilterChipRow(start: RvChipPick, end: RvChipPick, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Chip(start)
        Spacer(Modifier.weight(1f))
        Chip(end)
    }
}

@Composable
private fun Chip(pick: RvChipPick) {
    RvMenuAnchor(pick.items) { open, actions -> RvFilterChip(pick.label, open, actions) }
}
