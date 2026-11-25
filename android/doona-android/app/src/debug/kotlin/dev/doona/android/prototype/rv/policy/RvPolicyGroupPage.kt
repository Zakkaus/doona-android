package dev.doona.android.prototype.rv.policy

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
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
import dev.doona.android.designsystem.rv.RvInline
import dev.doona.android.designsystem.rv.RvLatencyChange
import dev.doona.android.designsystem.rv.RvLatencyText
import dev.doona.android.designsystem.rv.RvMenuAnchor
import dev.doona.android.designsystem.rv.RvPageHeader
import dev.doona.android.designsystem.rv.RvPrimaryButton
import dev.doona.android.designsystem.rv.RvRow
import dev.doona.android.designsystem.rv.RvScrollPage
import dev.doona.android.designsystem.rv.RvSeeAll
import dev.doona.android.designsystem.rv.RvSize
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.designsystem.rv.RvText
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.designsystem.rv.rvRowSelect
import dev.doona.android.designsystem.rv.rvSystemScheme
import dev.doona.android.engine.ScoreGroup
import dev.doona.android.prototype.rv.data.RvNode
import dev.doona.android.prototype.rv.nodes.RvStatusChip
import dev.doona.android.prototype.rv.nodes.SEPARATOR
import dev.doona.android.prototype.rv.nodes.latencyText

/**
 * A group's page, pushed from its row or the main node card, in the 節點 page's grammar: the back disc and the group's
 * name as the large title; how the group picks and how many nodes it has, and the node it uses in the nodes page's
 * status chip; then its nodes fastest first in 節點's rows, the chosen one badged. In a selector a tap picks the node; a
 * url-test group picks for itself, so its rows do not respond, a note says how it picks and its choice's line begins
 * with 自動.
 * 測試此組 floats at the bottom as the page's primary button.
 * A Score group ([score], what honk's `scoreState` reports for it) picks per network: in place of the status chip, a card
 * for TCP and one for UDP say what each chose and how the challengers compare ([RvScoreCard]); the chosen rows' line
 * begins with the networks that chose them. With [joint] (a sample of honk's network mode) the cards follow it
 * ([RvScoreJointCards]), and the switch 分開選擇 TCP 與 UDP calls [onSeparate].
 * A selector that can choose per network ([networks], a sample) shows its choice in [RvNetworkCard] in place of the
 * chip: the switch 分開選擇 TCP 與 UDP ([onApart]), then one row, or a row per network while the switch is on, each
 * opening the node picker for its network ([onOpenPicker]; null for both). The 節點 card badges both choices and, while
 * the switch is on, begins their line with the networks (`TCP/UDP` for a node both chose). While the switch is on, a tap
 * on a node first asks which network it is for (TCP, UDP or both, [RvNetworkMenu]) and then calls [onSelectFor]; while it
 * is off a tap picks for both at once.
 */
@Composable
internal fun RvPolicyGroupPage(
    group: RvPolicyGroup,
    onBack: () -> Unit,
    onSelect: (String) -> Unit,
    onTest: () -> Unit,
    score: ScoreGroup? = null,
    joint: RvScoreJoint? = null,
    onSeparate: (Boolean) -> Unit = {},
    networks: RvNetworkChoice? = null,
    onOpenPicker: (RvNetwork?) -> Unit = {},
    onApart: (Boolean) -> Unit = {},
    onSelectFor: (String, RvNetwork?) -> Unit = { id, _ -> onSelect(id) },
) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val name = stringResource(group.name)
    RvScrollPage(
        name,
        insets,
        onBack = onBack,
        bottomAction = { RvPrimaryButton(stringResource(R.string.rv_policy_test_group), onTest) },
    ) { titleBlock ->
        val node = group.selected
        RvPageHeader(
            name,
            titleBlock,
            insets,
            caption = { Caption(group) },
            control = if (score != null || networks != null) {
                null
            } else {
                { RvStatusChip(R.drawable.rv_nodes_ic_check_circle, stringResource(R.string.rv_policy_selected, node.name, latencyText(node.latencyMs)), Rv.colors.positive) }
            },
        )
        RvGutter {
            if (score != null && joint != null) {
                RvScoreJointCards(score, joint, group.members, onSeparate)
            } else if (score != null) {
                RvScoreCard(stringResource(R.string.ui_tcp), score.tcp, group.members)
                RvScoreCard(stringResource(R.string.ui_udp), score.udp, group.members)
            }
            if (networks != null) RvNetworkCard(group.members, networks, onOpenPicker, onApart)
            NodesCard(group, score, networks, onSelect, onSelectFor)
        }
    }
}

/** The header's caption: the kind and the node count, and for url-test the rule it picks by. */
@Composable
private fun Caption(group: RvPolicyGroup) {
    RvCaption(group.caption(), group.kind.glyph)
    if (group.kind == RvGroupKind.Auto) RvCaption(stringResource(R.string.rv_policy_auto_note))
}

/** How the group picks and how many nodes it has: the header's caption, and the node picker's for the group. */
@Composable
internal fun RvPolicyGroup.caption(): String =
    stringResource(kind.label) + SEPARATOR + pluralStringResource(R.plurals.rv_policy_node_count, members.size, members.size)

/** The nodes, fastest first: the first [ROWS], then 查看全部, which opens the rest in place. */
@Composable
private fun NodesCard(group: RvPolicyGroup, score: ScoreGroup?, networks: RvNetworkChoice?, onSelect: (String) -> Unit, onSelectFor: (String, RvNetwork?) -> Unit) {
    var all by rememberSaveable(group.id) { mutableStateOf(false) }
    RvCard {
        RvCardHeader(stringResource(R.string.rv_policy_nodes))
        val nodes = group.byLatency
        for (node in if (all) nodes else nodes.take(ROWS)) {
            if (networks?.apart == true) {
                // The switch is on: the tap asks which network the node is for, in a menu under the row's end.
                RvMenuAnchor(rvNetworkMenu { onSelectFor(node.id, it) }) { open, actions -> NodeRow(group, score, networks, node, actions) { open?.invoke() } }
            } else {
                NodeRow(group, score, networks, node, Modifier, onSelect)
            }
        }
        if (!all && nodes.size > ROWS) RvSeeAll(pluralStringResource(R.plurals.rv_policy_see_all_nodes, nodes.size, nodes.size)) { all = true }
    }
}

/**
 * A node as 節點 lists it; a selector's row picks it, and the chosen row carries the check badge (and 自動 in url-test).
 * While a latency test of every node, or of this node, runs ([LocalRvTesting], [LocalRvTestingNodes]) a busy bar stands at
 * its end in the room of the latency and its change, as in the node picker ([RvLatencyChange]): the name keeps its width
 * and lines. [actions] carries a menu's items for a screen reader (the network-choice menu while the switch is on).
 */
@Composable
private fun NodeRow(group: RvPolicyGroup, score: ScoreGroup?, choice: RvNetworkChoice?, node: RvNode, actions: Modifier, onSelect: (String) -> Unit) {
    val networks = score?.let { chosenOn(it, node.id) } ?: choice?.network(node.id)
    val chosen = when {
        score != null -> networks != null
        choice != null -> choice.chosen(node.id)
        else -> node.id == group.selectedId
    }
    val auto = group.kind != RvGroupKind.Select
    val picked = stringResource(R.string.rv_nodes_in_use)
    val testing = LocalRvTesting.current || node.id in LocalRvTestingNodes.current
    val pick = if (auto) {
        if (chosen) Modifier.semantics { stateDescription = picked } else Modifier
    } else {
        Modifier.rvRowSelect(chosen, { onSelect(node.id) }).then(actions)
    }
    RvRow(
        { RvFlag(node.place.flag, RvSize.rowDisc, current = chosen) },
        node.name,
        listOfNotNull(networks ?: stringResource(R.string.rv_policy_auto).takeIf { chosen && auto }, node.protocol, stringResource(node.place.label)).joinToString(SEPARATOR),
        pick,
        titleEnd = { RvLatencyText(node.latencyMs, node.timedOut) },
        subtitleEnd = node.changeMs?.let { { RvDelta(it) } },
        endBusy = testing,
        titleKeepWords = false,
    )
}

/** The networks on which a Score group chose [nodeId] (TCP, UDP or both), or null where it chose it on neither. */
@Composable
private fun chosenOn(score: ScoreGroup, nodeId: String): String? = listOfNotNull(
    stringResource(R.string.ui_tcp).takeIf { score.tcp.selectedId == nodeId },
    stringResource(R.string.ui_udp).takeIf { score.udp.selectedId == nodeId },
).takeIf { it.isNotEmpty() }?.joinToString("/")

/** As many rows as 節點's region cards show before their 查看全部, and a few more: a group is a list to pick from. */
private const val ROWS = 8

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RvPolicyGroupPagePreview() {
    val dark = isSystemInDarkTheme()
    var group by remember { mutableStateOf(RvPolicyData.streaming) }
    RvTheme(rvSystemScheme(dark), dark) { RvPolicyGroupPage(group, onBack = {}, onSelect = { group = group.choosing(it) }, onTest = {}) }
}
