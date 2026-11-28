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
import dev.doona.android.designsystem.rv.RvNameDisc
import dev.doona.android.designsystem.rv.RvNodesSize
import dev.doona.android.designsystem.rv.RvPageHeader
import dev.doona.android.designsystem.rv.RvPrimaryButton
import dev.doona.android.designsystem.rv.RvRow
import dev.doona.android.designsystem.rv.RvScrollPage
import dev.doona.android.designsystem.rv.RvSeeAll
import dev.doona.android.designsystem.rv.RvSegmented
import dev.doona.android.designsystem.rv.RvSize
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.designsystem.rv.RvStatusChip
import dev.doona.android.designsystem.rv.RvSwitchRow
import dev.doona.android.designsystem.rv.SEPARATOR
import dev.doona.android.designsystem.rv.latencyText
import dev.doona.android.designsystem.rv.rvMarkName
import dev.doona.android.designsystem.rv.rvRowSelect
import dev.doona.android.designsystem.rv.rvShownName
import dev.doona.android.feature.model.RvGroupKind
import dev.doona.android.feature.model.RvNode
import dev.doona.android.feature.model.RvPolicyGroup
import dev.doona.android.feature.model.text
import dev.doona.android.feature.nodes.ui.NODE_SEPARATOR
import dev.doona.android.feature.nodes.ui.RvApplyCard
import dev.doona.android.feature.nodes.ui.RvApplyTo
import dev.doona.android.feature.policies.NetworkCard
import dev.doona.android.feature.policies.RvApplyChoice

/**
 * A selector group's page for the samples of nbtap and nbmany, built only from the library's parts and the policy page's
 * own grammar (`RvPolicyGroupPage`): the back disc and the group's name as the title, the kind and node count, the node in
 * use in the status chip, then the nodes fastest first, the chosen one badged.
 *
 * With [choice] given, 分開選擇 TCP 與 UDP stands in 目前使用中 as it does on the approved netsel-b page: the chip is
 * left out, a row for each network says its node. [applyTo] non-null adds the 套用到 control above the node list: it
 * only exists while the switch is on, and a tap on a node goes to the selected network, with no menu. [rows] nodes are
 * listed before 查看全部, which opens the rest in place.
 */
@Composable
internal fun RvGroupSamplePage(
    group: RvPolicyGroup,
    choice: RvApplyChoice?,
    applyTo: RvApplyTo?,
    rows: Int,
    modifier: Modifier = Modifier,
) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val name = group.name.text()
    var state by remember { mutableStateOf(choice) }
    var apply by remember { mutableStateOf(applyTo) }
    val current = state
    RvScrollPage(name, insets, modifier, onBack = {}, bottomAction = { RvPrimaryButton(stringResource(R.string.rv_policy_test_group), {}) }) { titleBlock ->
        RvPageHeader(
            name,
            titleBlock,
            insets,
            caption = { RvGroupCaption(group) },
            control = if (current == null) ({ RvGroupChip(group.selected!!) }) else null,
        )
        RvGutter {
            if (current != null) {
                NetworkCard(group, current, {}) { state = it.apart(!it.apart) }
                if (current.apart) apply?.let { selected -> RvApplyCard(selected) { apply = it } }
            }
            NodesCard(group, current, apply ?: RvApplyTo.Both, rows) { id, to -> state = state?.tapped(id, to) }
        }
    }
}

/** The line under a group page's title: its kind and how many nodes it has. */
@Composable
internal fun RvGroupCaption(group: RvPolicyGroup) {
    val count = pluralStringResource(R.plurals.rv_policy_node_count, group.members.size, group.members.size)
    RvCaption(stringResource(group.kind.label) + SEPARATOR + count, group.kind.glyph)
}

/** The group page's chip: the node in use and its latency. */
@Composable
internal fun RvGroupChip(node: RvNode) {
    RvStatusChip(R.drawable.rv_nodes_ic_check_circle, stringResource(R.string.rv_policy_selected, node.name, latencyText(node.latencyMs)), Rv.colors.positive)
}

/** The nodes, fastest first: [rows] of them, then 查看全部. A row's tap picks the node for [apply]'s network. */
@Composable
private fun NodesCard(group: RvPolicyGroup, choice: RvApplyChoice?, apply: RvApplyTo, rows: Int, onPick: (String, RvApplyTo) -> Unit) {
    var all by remember { mutableStateOf(false) }
    val nodes = group.byLatency
    RvCard {
        RvCardHeader(stringResource(R.string.rv_policy_nodes))
        for (node in if (all) nodes else nodes.take(rows)) NodeRow(node, choice?.chosen(node.id) ?: (node.id == group.selectedId)) { onPick(node.id, apply) }
        if (!all && nodes.size > rows) RvSeeAll(pluralStringResource(R.plurals.rv_policy_see_all_nodes, nodes.size, nodes.size)) { all = true }
    }
}

/** A node as the policy page lists it: the flag (badged when chosen), name, protocol and place, latency and its change. */
@Composable
private fun NodeRow(node: RvNode, chosen: Boolean, onPick: () -> Unit) {
    val testing = LocalRvTesting.current || node.id in LocalRvTestingNodes.current
    RvRow(
        { RvFlag(node.place.flag, RvSize.rowDisc, current = chosen) },
        node.name,
        listOfNotNull(node.protocol, stringResource(node.place.label)).joinToString(NODE_SEPARATOR),
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
