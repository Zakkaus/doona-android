package dev.doona.android.feature.policies

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.doona.android.R
import dev.doona.android.designsystem.rv.LocalFrameInsets
import dev.doona.android.designsystem.rv.RvCaption
import dev.doona.android.designsystem.rv.RvCard
import dev.doona.android.designsystem.rv.RvCardHeader
import dev.doona.android.designsystem.rv.RvEmptyCaption
import dev.doona.android.designsystem.rv.RvFilterChipRow
import dev.doona.android.designsystem.rv.RvGutter
import dev.doona.android.designsystem.rv.RvLatencyChange
import dev.doona.android.designsystem.rv.RvNameDisc
import dev.doona.android.designsystem.rv.RvNodesSize
import dev.doona.android.designsystem.rv.RvNotice
import dev.doona.android.designsystem.rv.RvPageHeader
import dev.doona.android.designsystem.rv.RvPrimaryButton
import dev.doona.android.designsystem.rv.RvRow
import dev.doona.android.designsystem.rv.RvScrollPage
import dev.doona.android.designsystem.rv.RvSearchField
import dev.doona.android.designsystem.rv.RvSearchSlot
import dev.doona.android.designsystem.rv.RvSegmented
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.designsystem.rv.RvSwitchRow
import dev.doona.android.designsystem.rv.SEPARATOR
import dev.doona.android.designsystem.rv.rvMarkName
import dev.doona.android.designsystem.rv.rvRowSelect
import dev.doona.android.designsystem.rv.rvShownName
import dev.doona.android.feature.model.RV_INLINE_KEY
import dev.doona.android.feature.model.RvGroupKind
import dev.doona.android.feature.model.RvNode
import dev.doona.android.feature.model.RvNodeSort
import dev.doona.android.feature.model.RvPolicyGroup
import dev.doona.android.feature.model.RvSubscription
import dev.doona.android.feature.model.text
import dev.doona.android.feature.nodes.ui.RvNodePickRow
import dev.doona.android.feature.nodes.ui.RvNodeSection
import dev.doona.android.feature.nodes.ui.RvRowsComposed
import dev.doona.android.feature.nodes.ui.matches

/** A group page's taps: back, a node's row ([tap]) and its detail ([detail]), the switch, 套用到, and 測試群組. */
internal class RvGroupActions(
    val back: () -> Unit = {},
    val tap: (String) -> Unit = {},
    val detail: (String) -> Unit = {},
    val apart: (Boolean) -> Unit = {},
    val apply: (RvApplyTo) -> Unit = {},
    val test: () -> Unit = {},
)

/** What a group page shows besides the group: its nodes' subscriptions, each network's node, 套用到, whether Score picks. */
internal class RvGroupContent(
    val group: RvPolicyGroup,
    val subscriptions: List<RvSubscription>,
    val choice: RvApplyChoice,
    val apply: RvApplyTo,
    val score: Boolean,
    val nowEpochMs: Long,
)

/**
 * What the group page's search field and two chips hold when it opens: [source] is the picked subscription's id, or
 * [RV_INLINE_KEY] for 組態內, null for every source.
 */
internal class RvGroupFilter(val query: String = "", val source: String? = null, val order: RvNodeSort = RvNodeSort.Latency)

/**
 * A selector group's page, the approved s1125d sample: the title and caption; 使用中 with the switch 分開選擇 TCP/UDP and
 * the node of each network; while the switch is on, the 套用到 control (TCP/UDP｜TCP｜UDP) that says which network the
 * next tap on a node is for; the search field; then the nodes in a card per subscription, as the node picker has them,
 * and the nodes added by hand under 組態內 last, a section folded from [RV_SECTIONS_OPEN_UP_TO] + 1 sections on, except
 * the sections that hold a chosen node. Names carry emoji drawn as our icons (emoji1). A row chosen on a network begins its line with it.
 *
 * In a Score group ([RvGroupContent.score]) Score picks each network's node itself, so a tap chooses nothing and the
 * 套用到 control is not there; the chosen rows begin with Score's mark (Score for both networks, Score TCP, Score UDP).
 *
 * Under the search field stand the 節點 page's two chips: the subscription (one picked, only its section shows, open; or
 * all of them) and the order (延遲, 穩定, 名稱 or 地區, applied inside each section). They filter the list below them,
 * not the 使用中 or the 套用到 card, and are the page's own; [filter] is what they hold to begin with. A failed action's
 * [banner] heads the cards.
 */
@Composable
internal fun RvGroupPage(
    content: RvGroupContent,
    actions: RvGroupActions,
    modifier: Modifier = Modifier,
    banner: RvNotice? = null,
    filter: RvGroupFilter = RvGroupFilter(),
) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val group = content.group
    val choice = content.choice
    val name = group.name.text()
    var typed by rememberSaveable { mutableStateOf(filter.query) }
    var picked by rememberSaveable { mutableStateOf(filter.source) }
    var sorted by rememberSaveable { mutableStateOf(filter.order) }
    val labels = group.members.associate { it.id to stringResource(it.place.label) }
    val inline = group.members.any { it.inline }
    val sources = content.subscriptions.map { it.id } + listOfNotNull(RV_INLINE_KEY.takeIf { inline })
    // A pick whose subscription an update removed falls back to every subscription, chip and list alike.
    val pick = picked?.takeIf { it in sources }
    val scoped = group.members.filter { pick == null || it.source == pick }
    val matching = scoped.filter { it.matches(typed.trim(), labels.getValue(it.id)) }.mapTo(mutableSetOf()) { it.id }
    // The node each network's choice ends on: a chosen node, or the node a chosen member group ends on.
    val ends = group.members.filter { choice.chosen(it.id) } + group.groups.filter { choice.chosen(it.id) }.mapNotNull { it.node }
    val holders = ends.map { it.source }.toSet()
    val open = sources.size <= RV_SECTIONS_OPEN_UP_TO
    RvScrollPage(name, insets, modifier, onBack = actions.back, bottomAction = { RvPrimaryButton(stringResource(R.string.rv_policy_test_group), actions.test) }) { titleBlock ->
        RvPageHeader(name, titleBlock, insets, caption = { Caption(group, content.score) })
        RvGutter(banner = banner) {
            NetworkCard(group, choice) { actions.apart(!it.apart) }
            if (choice.apart && !content.score) ApplyCard(content.apply, actions.apply)
            if (group.groups.isNotEmpty()) MemberGroupsCard(group, choice, content.score, actions.tap)
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = RvSpace.gutter)) {
            RvSearchSlot { RvSearchField(null, typed, { typed = it }, it) }
            val (subChip, orderChip) = rvGroupChips(content.subscriptions, inline, pick, sorted, { picked = it }, { sorted = it })
            RvFilterChipRow(subChip, orderChip)
            Spacer(Modifier.height(RvSpace.controlGap))
        }
        val subs = content.subscriptions.associateBy { it.id }
        sources.filter { pick == null || it == pick }.forEach { source ->
            // A new pick starts its section afresh: open, as a section alone on the page is.
            key(pick, source) {
                RvNodeSection(
                    subs[source],
                    rvSectionOrder(scoped.filter { it.source == source }, sorted),
                    matching,
                    labels,
                    choice.tcpId,
                    searching = typed.isNotBlank(),
                    onPick = actions.tap,
                    onDetail = actions.detail,
                    rows = RvRowsComposed(0) { Int.MAX_VALUE },
                    nowEpochMs = content.nowEpochMs,
                    modifier = Modifier,
                    initiallyOpen = open || pick != null || source in holders,
                    row = { node, label, _, order ->
                        SectionRow(node, label, order, choice, lead(choice, node.id, content.score), actions)
                    },
                )
            }
        }
        if (group.members.isNotEmpty() && matching.isEmpty()) {
            RvGutter { RvEmptyCaption(stringResource(R.string.rv_group_no_match), stringResource(R.string.rv_conn_none_line)) }
        }
    }
}

/**
 * The line under the title: how the group picks and how many nodes and groups it has, as 策略's group row says it. A Score
 * group ([score]) is named as the templates name it.
 */
@Composable
private fun Caption(group: RvPolicyGroup, score: Boolean) {
    val kind = if (score) RvGroupKind.Score else group.kind
    RvCaption(groupLine(group, kind), kind.glyph)
}

/**
 * The groups among the members (honk lets a group list groups), above the search, which filters nodes: each under its
 * kind's glyph, flagged while chosen, its name over the networks that chose it and how it picks, and the node its own
 * choice ends on with that node's latency. A tap chooses the group as a tap on a node chooses the node.
 */
@Composable
private fun MemberGroupsCard(group: RvPolicyGroup, choice: RvApplyChoice, score: Boolean, onTap: (String) -> Unit) {
    RvCard {
        RvCardHeader(stringResource(R.string.rv_policy_groups))
        group.groups.forEach { member ->
            val chosen = choice.chosen(member.id)
            RvGroupRow(
                member.kind.glyph,
                member.name.text(),
                listOfNotNull(lead(choice, member.id, score), stringResource(member.kind.label)).joinToString(SEPARATOR),
                member.node,
                Modifier.rvRowSelect(chosen, { onTap(member.id) }),
                current = chosen,
            )
        }
    }
}

/** A node of a section: the picker's row, its name's emoji drawn as our icons, [mark] before its line. */
@Composable
private fun SectionRow(node: RvNode, label: String, order: Int, choice: RvApplyChoice, mark: String?, actions: RvGroupActions) {
    val shown = rvShownName(node.name)
    RvNodePickRow(
        node,
        label,
        choice.chosen(node.id),
        order,
        actions.tap,
        onDetail = actions.detail,
        title = shown.text.ifEmpty { rvMarkName(shown.mark, label) },
        lead = mark,
        mark = { current -> RvNameDisc(shown.mark, node.place.flag, current, RvNodesSize.rowFlag) },
    )
}

/**
 * What a row chosen on a network says before its line: with the switch off nothing (one node serves both; in a Score group
 * Score's mark); on, the networks that chose it (TCP, UDP, or TCP/UDP for both, q-groupboth), and in a Score group Score's
 * mark: Score for both networks, Score TCP or Score UDP for one (sc3).
 */
@Composable
private fun lead(choice: RvApplyChoice, id: String, score: Boolean): String? {
    val mark = stringResource(R.string.rv_score_mark)
    if (!choice.apart) return mark.takeIf { score && id == choice.tcpId }
    val tcp = id == choice.tcpId
    val udp = id == choice.udpId
    val networks = listOfNotNull(stringResource(R.string.ui_tcp).takeIf { tcp }, stringResource(R.string.ui_udp).takeIf { udp }).joinToString("/")
    return when {
        networks.isEmpty() -> null
        score -> if (tcp && udp) mark else "$mark $networks"
        else -> networks
    }
}

/** 目前使用中: the switch 分開選擇 TCP/UDP, then a row for each network, its line beginning with the network. */
@Composable
internal fun NetworkCard(group: RvPolicyGroup, choice: RvApplyChoice, onApart: (RvApplyChoice) -> Unit) {
    val node = group::nodeOf
    RvCard {
        RvCardHeader(stringResource(R.string.rv_nodes_in_use))
        RvSwitchRow(R.drawable.rv_sym_call_split, stringResource(R.string.rv_group_apart), choice.apart, onChange = { onApart(choice) })
        if (choice.apart) {
            node(choice.tcpId)?.let { NetworkRow(it, stringResource(R.string.ui_tcp)) }
            node(choice.udpId)?.let { NetworkRow(it, stringResource(R.string.ui_udp)) }
        } else {
            node(choice.tcpId)?.let { NetworkRow(it, null) }
        }
    }
}

@Composable
private fun NetworkRow(node: RvNode, network: String?) {
    val place = stringResource(node.place.label)
    val shown = rvShownName(node.name)
    RvRow(
        { RvNameDisc(shown.mark, node.place.flag, current = true) },
        shown.text.ifEmpty { rvMarkName(shown.mark, place) },
        listOfNotNull(network, node.protocol, place).joinToString(SEPARATOR),
        onClick = {},
        titleKeepWords = false,
    ) { RvLatencyChange(node.latencyMs, node.changeMs, node.timedOut) }
}

/**
 * 套用到 (nbtap): the library's segmented control, the one 策略's mode switch uses, in a card above the node list as
 * 'Top movers' holds its own ([RvNodesSize.topRowGap] and the card's foot as there). TCP/UDP first (nborder).
 */
@Composable
internal fun ApplyCard(selected: RvApplyTo, onSelect: (RvApplyTo) -> Unit) {
    val options = RvApplyTo.entries
    val labels = listOf(stringResource(R.string.rv_network_both), stringResource(R.string.ui_tcp), stringResource(R.string.ui_udp))
    RvCard {
        RvCardHeader(stringResource(R.string.rv_network_apply_to))
        RvSegmented(labels, options.indexOf(selected), { onSelect(options[it]) }, Modifier.padding(horizontal = RvSpace.rowPadding))
        Spacer(Modifier.height(RvNodesSize.topRowGap))
    }
}

/**
 * A group with this many subscriptions or fewer lists every one open; with more, only the section that holds the chosen
 * node starts open. Two sections are about a screenful, so the nodes are in reach by scrolling; from three on a page of
 * open sections is several screens, and the 訂閱 rows are the index to find a section by.
 * (nbmany)
 */
internal const val RV_SECTIONS_OPEN_UP_TO = 2

/** The section a node is listed under: its subscription's id, or [RV_INLINE_KEY] for a node added by hand. */
private val RvNode.source: String get() = subscriptionId ?: RV_INLINE_KEY
