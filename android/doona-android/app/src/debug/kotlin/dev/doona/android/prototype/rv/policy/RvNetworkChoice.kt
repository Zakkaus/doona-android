package dev.doona.android.prototype.rv.policy

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.res.stringResource
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RvCard
import dev.doona.android.designsystem.rv.RvCardHeader
import dev.doona.android.designsystem.rv.RvFlag
import dev.doona.android.designsystem.rv.RvLatencyChange
import dev.doona.android.designsystem.rv.RvMenuItem
import dev.doona.android.designsystem.rv.RvRow
import dev.doona.android.designsystem.rv.RvSize
import dev.doona.android.designsystem.rv.RvSwitchRow
import dev.doona.android.prototype.rv.data.RvData
import dev.doona.android.prototype.rv.data.RvNode
import dev.doona.android.prototype.rv.data.RvRuleOutbound
import dev.doona.android.prototype.rv.nodes.RvNodePicker
import dev.doona.android.prototype.rv.nodes.SEPARATOR

/** One of a group's two networks, as the engine's `select(group, member, network)` takes it; null there is both. */
internal enum class RvNetwork(val label: Int) {
    Tcp(R.string.ui_tcp),
    Udp(R.string.ui_udp),
}

/**
 * A selector group's choice per network (a sample; the engine's group has a TCP and a UDP selection): the node TCP uses
 * ([tcpId]) and the node UDP uses ([udpId]), and whether the page's switch 分開選擇 TCP 與 UDP is on ([apart]).
 */
@Immutable
internal class RvNetworkChoice(val tcpId: String, val udpId: String, val apart: Boolean) {
    /** Whether the two networks use different nodes. */
    val differ: Boolean get() = tcpId != udpId

    /** Whether [nodeId] is a choice of either network. */
    fun chosen(nodeId: String): Boolean = nodeId == tcpId || nodeId == udpId

    /** The node [network] uses; TCP's for both. */
    fun nodeFor(network: RvNetwork?): String = if (network == RvNetwork.Udp) udpId else tcpId

    /** [nodeId] picked for [network], or for both when it is null. */
    fun picked(nodeId: String, network: RvNetwork?): RvNetworkChoice = RvNetworkChoice(
        if (network == RvNetwork.Udp) tcpId else nodeId,
        if (network == RvNetwork.Tcp) udpId else nodeId,
        apart,
    )

    /** The switch set to [on]; off, UDP goes back to TCP's node, as one choice for both. */
    fun apart(on: Boolean): RvNetworkChoice = if (on) RvNetworkChoice(tcpId, udpId, true) else RvNetworkChoice(tcpId, tcpId, false)

    /**
     * The network that chose [nodeId], as Score's mark names it: only while the two choices differ, so a node both use
     * reads as before.
     */
    @Composable
    fun network(nodeId: String): String? = when {
        !differ -> null
        nodeId == tcpId -> stringResource(R.string.ui_tcp)
        nodeId == udpId -> stringResource(R.string.ui_udp)
        else -> null
    }
}

/**
 * The group's choice on its page, in 規則's 主節點 card: 目前使用中, then the switch 分開選擇 TCP 與 UDP ([onApart]), then
 * the node in use, flagged as the node in use, its line `協定 · 地區` and its latency over its change. While the switch is
 * on there is a row for each network, its line beginning with the network (`TCP · VLESS · 臺灣`). A row opens the node
 * picker for its network, or for both while the switch is off ([onOpen]).
 */
@Composable
internal fun RvNetworkCard(members: List<RvNode>, choice: RvNetworkChoice, onOpen: (RvNetwork?) -> Unit, onApart: (Boolean) -> Unit) {
    RvCard {
        RvCardHeader(stringResource(R.string.rv_nodes_in_use))
        RvSwitchRow(R.drawable.rv_sym_call_split, stringResource(R.string.rv_network_apart), choice.apart, onChange = onApart)
        if (choice.apart) {
            for (network in RvNetwork.entries) ChoiceRow(members.first { it.id == choice.nodeFor(network) }, stringResource(network.label)) { onOpen(network) }
        } else {
            ChoiceRow(members.first { it.id == choice.tcpId }, null) { onOpen(null) }
        }
    }
}

@Composable
private fun ChoiceRow(node: RvNode, network: String?, onOpen: () -> Unit) {
    RvRow(
        { RvFlag(node.place.flag, RvSize.rowDisc, current = true) },
        node.name,
        listOfNotNull(network, node.protocol, stringResource(node.place.label)).joinToString(SEPARATOR),
        onClick = onOpen,
    ) { RvLatencyChange(node.latencyMs, node.changeMs, node.timedOut) }
}

/**
 * What a tap on a node asks while the switch is on: which network the node is for, TCP or UDP (the switch's split glyph)
 * or both (設為主節點's glyph); [onPick] gets the network, null for both.
 */
@Composable
internal fun rvNetworkMenu(onPick: (RvNetwork?) -> Unit): List<RvMenuItem> =
    RvNetwork.entries.map { network -> RvMenuItem(stringResource(network.label), R.drawable.rv_sym_call_split) { onPick(network) } } +
        RvMenuItem(stringResource(R.string.rv_network_both), R.drawable.rv_nodes_ic_check_circle) { onPick(null) }

/**
 * The group's node picker as it is ([RvNodePicker]), opened from a row of [RvNetworkCard]: titled with the group's name,
 * its caption how the group picks, begun with the row's [network] when there is one, and that network's node checked.
 */
@Composable
internal fun RvNetworkPicker(group: RvPolicyGroup, choice: RvNetworkChoice, network: RvNetwork?, onPick: (String) -> Unit = {}) {
    val caption = group.caption()
    RvNodePicker(
        group.members,
        RvData.subscriptions,
        choice.nodeFor(network),
        onPick = onPick,
        onDetail = {},
        onClose = {},
        title = stringResource(group.name),
        caption = network?.let { stringResource(it.label) + SEPARATOR + caption } ?: caption,
    )
}

/**
 * 串流 (a selector) for the samples of choosing TCP and UDP apart: [shared] with the switch off, Taipei 04 on both
 * networks; [apart] with the switch on, Taipei 04 on TCP and Hong Kong 01 on UDP; [asked], the node whose network the
 * samples ask for; [rules], 活動's rule groups with 串流 apart; [rulesOneRegion], the same with UDP on another node in
 * TCP's region.
 */
internal object RvNetworkSample {
    val group: RvPolicyGroup = RvPolicyData.streaming
    val shared = RvNetworkChoice(group.selectedId, group.selectedId, apart = false)
    val apart = RvNetworkChoice(group.selectedId, UDP_NODE, apart = true)

    /** The fastest node neither network chose: the first such row on 節點's card. */
    val asked: RvNode = group.byLatency.first { !apart.chosen(it.id) }

    val rules: List<RvRuleOutbound> = rulesWithUdp(UDP_NODE)
    val rulesOneRegion: List<RvRuleOutbound> = rulesWithUdp(group.members.first { it.place == group.selected.place && it.id != group.selectedId }.id)

    private fun rulesWithUdp(udp: String) = RvData.ruleOutbounds.map {
        if (it.group == group.name) RvRuleOutbound(it.group, it.nodeId, it.bytes, udp) else it
    }
}

private const val UDP_NODE = "hk-01"
