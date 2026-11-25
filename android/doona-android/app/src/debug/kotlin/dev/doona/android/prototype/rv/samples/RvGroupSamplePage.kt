package dev.doona.android.prototype.rv.samples

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.doona.android.R
import dev.doona.android.designsystem.rv.LocalFrameInsets
import dev.doona.android.designsystem.rv.LocalRvTesting
import dev.doona.android.designsystem.rv.LocalRvTestingNodes
import dev.doona.android.designsystem.rv.Rv
import dev.doona.android.designsystem.rv.RvCaption
import dev.doona.android.designsystem.rv.RvCard
import dev.doona.android.designsystem.rv.RvCardHeader
import dev.doona.android.designsystem.rv.RvDelta
import dev.doona.android.designsystem.rv.RvFlag
import dev.doona.android.designsystem.rv.RvGutter
import dev.doona.android.designsystem.rv.RvLatencyChange
import dev.doona.android.designsystem.rv.RvLatencyText
import dev.doona.android.designsystem.rv.RvPageHeader
import dev.doona.android.designsystem.rv.RvPrimaryButton
import dev.doona.android.designsystem.rv.RvRow
import dev.doona.android.designsystem.rv.RvScrollPage
import dev.doona.android.designsystem.rv.RvSeeAll
import dev.doona.android.designsystem.rv.RvSegmented
import dev.doona.android.designsystem.rv.RvSize
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.designsystem.rv.RvSwitchRow
import dev.doona.android.designsystem.rv.rvRowSelect
import dev.doona.android.prototype.rv.data.RvNode
import dev.doona.android.prototype.rv.nodes.RvNodesSize
import dev.doona.android.prototype.rv.nodes.RvStatusChip
import dev.doona.android.prototype.rv.nodes.SEPARATOR
import dev.doona.android.prototype.rv.nodes.latencyText
import dev.doona.android.prototype.rv.policy.RvGroupKind
import dev.doona.android.prototype.rv.policy.RvPolicyGroup

/** What the nodes card ends with when the group has more nodes than it lists. */
internal enum class RvMoreNodes {
    /** The page as approved: 查看全部 N 個 opens the rest in place. */
    InPlace,

    /** Revolut's 'See all' row at the card's foot, 全部節點: it opens the node picker (nbmany). */
    Picker,
}

/**
 * A selector group's page for the samples of nbtap and nbmany, built only from the library's parts and the policy page's
 * own grammar (`RvPolicyGroupPage`): the back disc and the group's name as the title, the kind and node count, the node in
 * use in the status chip, then the nodes fastest first, the chosen one badged.
 *
 * With [choice] given, 分開選擇 TCP 與 UDP stands in 目前使用中 as it does on the approved netsel-b page: the chip is
 * left out, a row for each network says its node. [applyTo] non-null adds the 套用到 control above the node list: it
 * only exists while the switch is on, and a tap on a node goes to the selected network, with no menu. [rows] nodes are
 * listed before [more].
 */
@Composable
internal fun RvGroupSamplePage(
    group: RvPolicyGroup,
    choice: RvApplyChoice?,
    applyTo: RvApplyTo?,
    rows: Int,
    more: RvMoreNodes,
    modifier: Modifier = Modifier,
) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val name = stringResource(group.name)
    var state by remember { mutableStateOf(choice) }
    var apply by remember { mutableStateOf(applyTo) }
    val current = state
    RvScrollPage(name, insets, modifier, onBack = {}, bottomAction = { RvPrimaryButton(stringResource(R.string.rv_policy_test_group), {}) }) { titleBlock ->
        RvPageHeader(
            name,
            titleBlock,
            insets,
            caption = { Caption(group) },
            control = if (current == null) {
                { RvStatusChip(R.drawable.rv_nodes_ic_check_circle, stringResource(R.string.rv_policy_selected, group.selected.name, latencyText(group.selected.latencyMs)), Rv.colors.positive) }
            } else {
                null
            },
        )
        RvGutter {
            if (current != null) {
                NetworkCard(group, current) { state = it.apart(!it.apart) }
                if (current.apart) apply?.let { selected -> ApplyCard(selected) { apply = it } }
            }
            NodesCard(group, current, apply ?: RvApplyTo.Both, rows, more) { id, to -> state = state?.tapped(id, to) }
        }
    }
}

@Composable
private fun Caption(group: RvPolicyGroup) {
    val count = pluralStringResource(R.plurals.rv_policy_node_count, group.members.size, group.members.size)
    RvCaption(stringResource(group.kind.label) + SEPARATOR + count, group.kind.glyph)
}

/** 目前使用中: the switch 分開選擇 TCP 與 UDP, then a row for each network, its line beginning with the network. */
@Composable
private fun NetworkCard(group: RvPolicyGroup, choice: RvApplyChoice, onApart: (RvApplyChoice) -> Unit) {
    RvCard {
        RvCardHeader(stringResource(R.string.rv_nodes_in_use))
        RvSwitchRow(R.drawable.rv_sym_account_tree, sampleText("分開選擇 TCP/UDP", "分开选择 TCP/UDP", "Choose TCP/UDP apart"), choice.apart, onChange = { onApart(choice) })
        if (choice.apart) {
            NetworkRow(group.members.first { it.id == choice.tcpId }, stringResource(R.string.ui_tcp))
            NetworkRow(group.members.first { it.id == choice.udpId }, stringResource(R.string.ui_udp))
        } else {
            NetworkRow(group.members.first { it.id == choice.tcpId }, null)
        }
    }
}

@Composable
private fun NetworkRow(node: RvNode, network: String?) {
    RvRow(
        { RvFlag(node.place.flag, RvSize.rowDisc, current = true) },
        node.name,
        listOfNotNull(network, node.protocol, stringResource(node.place.label)).joinToString(SEPARATOR),
        onClick = {},
    ) { RvLatencyChange(node.latencyMs, node.changeMs, node.timedOut) }
}

/**
 * 套用到 (nbtap): the library's segmented control, the one 策略's mode switch uses, in a card above the node list as
 * 'Top movers' holds its own ([RvNodesSize.topRowGap] and the card's foot as there). 'TCP 與 UDP' first (nborder).
 */
@Composable
private fun ApplyCard(selected: RvApplyTo, onSelect: (RvApplyTo) -> Unit) {
    val options = RvApplyTo.entries
    val labels = listOf("TCP/UDP", stringResource(R.string.ui_tcp), stringResource(R.string.ui_udp))
    RvCard {
        RvCardHeader(sampleText("套用到", "应用到", "Apply to"))
        RvSegmented(labels, options.indexOf(selected), { onSelect(options[it]) }, Modifier.padding(horizontal = RvSpace.rowPadding))
        Spacer(Modifier.height(RvNodesSize.topRowGap))
    }
}

/** The nodes, fastest first: [rows] of them, then [more]. A row's tap picks the node for [apply]'s network. */
@Composable
private fun NodesCard(group: RvPolicyGroup, choice: RvApplyChoice?, apply: RvApplyTo, rows: Int, more: RvMoreNodes, onPick: (String, RvApplyTo) -> Unit) {
    var all by remember { mutableStateOf(false) }
    val nodes = group.byLatency
    RvCard {
        RvCardHeader(stringResource(R.string.rv_policy_nodes))
        for (node in if (all) nodes else nodes.take(rows)) NodeRow(node, choice?.chosen(node.id) ?: (node.id == group.selectedId)) { onPick(node.id, apply) }
        if (!all && nodes.size > rows) {
            when (more) {
                RvMoreNodes.InPlace -> RvSeeAll(pluralStringResource(R.plurals.rv_policy_see_all_nodes, nodes.size, nodes.size)) { all = true }
                RvMoreNodes.Picker -> RvSeeAll(stringResource(R.string.rv_nodes_all)) {}
            }
        }
    }
}

/** A node as the policy page lists it: the flag (badged when chosen), name, protocol and place, latency and its change. */
@Composable
private fun NodeRow(node: RvNode, chosen: Boolean, onPick: () -> Unit) {
    val testing = LocalRvTesting.current || node.id in LocalRvTestingNodes.current
    RvRow(
        { RvFlag(node.place.flag, RvSize.rowDisc, current = chosen) },
        node.name,
        listOfNotNull(node.protocol, stringResource(node.place.label)).joinToString(SEPARATOR),
        Modifier.rvRowSelect(chosen, onPick),
        titleEnd = { RvLatencyText(node.latencyMs, node.timedOut) },
        subtitleEnd = node.changeMs?.let { { RvDelta(it) } },
        endBusy = testing,
        titleKeepWords = false,
    )
}

/** The sample's words in the two languages the owner reads it in; the strings of a decided feature come with its port. */
@Composable
internal fun sampleText(zh: String, en: String): String = if (LocalConfiguration.current.locales[0].language == "zh") zh else en
