package dev.doona.android.feature.nodes.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RvCard
import dev.doona.android.designsystem.rv.RvCardHeader
import dev.doona.android.designsystem.rv.RvNodesSize
import dev.doona.android.designsystem.rv.RvSegmented
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.engine.Network

/** The network a tap on a node applies to while a group's TCP and UDP choices are apart, in the control's order (nborder). */
internal enum class RvApplyTo(val network: Network?) { Both(null), Tcp(Network.Tcp), Udp(Network.Udp) }

/**
 * 套用到 (decision 2026-11-25 14:37, nbtap; 16:30 TCP/UDP): the library's segmented control in a card above a node list,
 * as 'Top movers' holds its own ([RvNodesSize.topRowGap] and the card's foot as there). Shown while the group's TCP and
 * UDP choices are apart: pick the network, then tap the node. The group page and the main node's pickers share it.
 */
@Composable
internal fun RvApplyCard(selected: RvApplyTo, onSelect: (RvApplyTo) -> Unit) {
    val options = RvApplyTo.entries
    val labels = listOf(stringResource(R.string.rv_network_both), stringResource(R.string.ui_tcp), stringResource(R.string.ui_udp))
    RvCard {
        RvCardHeader(stringResource(R.string.rv_nodes_apply_to))
        RvSegmented(labels, options.indexOf(selected), { onSelect(options[it]) }, Modifier.padding(horizontal = RvSpace.rowPadding))
        Spacer(Modifier.height(RvNodesSize.topRowGap))
    }
}
