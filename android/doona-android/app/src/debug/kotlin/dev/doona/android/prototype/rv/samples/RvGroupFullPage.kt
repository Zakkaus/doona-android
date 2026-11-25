package dev.doona.android.prototype.rv.samples

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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.doona.android.R
import dev.doona.android.designsystem.rv.LocalFrameInsets
import dev.doona.android.designsystem.rv.RvCaption
import dev.doona.android.designsystem.rv.RvEmptyCaption
import dev.doona.android.designsystem.rv.RvFilterChipRow
import dev.doona.android.designsystem.rv.RvGutter
import dev.doona.android.designsystem.rv.RvNodesSize
import dev.doona.android.designsystem.rv.RvPageHeader
import dev.doona.android.designsystem.rv.RvPrimaryButton
import dev.doona.android.designsystem.rv.RvScrollPage
import dev.doona.android.designsystem.rv.RvSearchField
import dev.doona.android.designsystem.rv.RvSearchSlot
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.designsystem.rv.SEPARATOR
import dev.doona.android.feature.model.RvNode
import dev.doona.android.feature.model.RvNodeSort
import dev.doona.android.feature.model.RvPolicyGroup
import dev.doona.android.feature.model.RvSubscription
import dev.doona.android.feature.model.text
import dev.doona.android.feature.nodes.ui.RvNodePickRow
import dev.doona.android.feature.nodes.ui.RvNodeSection
import dev.doona.android.feature.nodes.ui.RvRowsComposed
import dev.doona.android.feature.nodes.ui.matches
import dev.doona.android.feature.policies.RvApplyChoice
import dev.doona.android.feature.policies.RvApplyTo
import dev.doona.android.prototype.rv.data.RvData

/**
 * The selector group's page with all its approved features at once: the title and caption; 使用中 with the switch 分開選擇
 * TCP/UDP and the node of each network; while the switch is on, the 套用到 control (TCP/UDP｜TCP｜UDP) that says which
 * network the next tap on a node is for; the search field; then the nodes in a card per subscription, as the node picker
 * has them, a subscription's section folded from [RV_SECTIONS_OPEN_UP_TO] + 1 subscriptions on, except the sections that
 * hold a chosen node. Names carry emoji drawn as our icons (emoji1). A row chosen on a network begins its line with it.
 *
 * With [score] the group is a Score group: Score picks each network's node itself, so a tap chooses nothing and the
 * 套用到 control is not there; the chosen rows begin with Score's mark (Score for both networks, Score TCP, Score UDP).
 * [query] is what the search field holds to begin with.
 *
 * Under the search field stand the 節點 page's two chips: the subscription (one picked, only its section shows, open; or all
 * of them) and the order (延遲, 穩定, 名稱 or 地區, applied inside each section). They filter the list below them, not the
 * 使用中 or the 套用到 card. [subscription] and [order] are what they hold to begin with.
 */
@Composable
internal fun RvGroupFullPage(
    sample: RvGroupFullSample.Sample,
    apart: Boolean,
    modifier: Modifier = Modifier,
    score: Boolean = false,
    query: String = "",
    subscription: String? = null,
    order: RvNodeSort = RvNodeSort.Latency,
) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val group = sample.group
    val name = group.name.text()
    var typed by remember { mutableStateOf(query) }
    var choice by remember { mutableStateOf(RvApplyChoice(sample.tcpId, if (apart) sample.udpId else sample.tcpId, apart)) }
    var apply by remember { mutableStateOf(RvApplyTo.Tcp) }
    val labels = group.members.associate { it.id to stringResource(it.place.label) }
    var picked by remember { mutableStateOf(subscription) }
    var sorted by remember { mutableStateOf(order) }
    val scoped = group.members.filter { picked == null || it.subscription == picked }
    val matching = scoped.filter { it.matches(typed.trim(), labels.getValue(it.id)) }.mapTo(mutableSetOf()) { it.id }
    val holders = group.members.filter { choice.chosen(it.id) }.map { it.subscription }.toSet()
    val open = sample.subscriptions.size <= RV_SECTIONS_OPEN_UP_TO
    RvScrollPage(name, insets, modifier, onBack = {}, bottomAction = { RvPrimaryButton(stringResource(R.string.rv_policy_test_group), {}) }) { titleBlock ->
        RvPageHeader(name, titleBlock, insets, caption = { Caption(group, score) })
        RvGutter {
            NetworkCard(group, choice) { choice = it.apart(!it.apart) }
            if (choice.apart && !score) ApplyCard(apply) { apply = it }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = RvSpace.gutter)) {
            RvSearchSlot { RvSearchField(null, typed, { typed = it }, it) }
            val (subChip, orderChip) = rvGroupChips(sample.subscriptions, picked, sorted, { picked = it }, { sorted = it })
            RvFilterChipRow(subChip, orderChip)
            Spacer(Modifier.height(RvSpace.controlGap))
        }
        sample.subscriptions.filter { picked == null || it.name == picked }.forEach { sub ->
            // A new pick starts its section afresh: open, as a section alone on the page is.
            key(picked, sub.name) {
                RvNodeSection(
                    sub,
                    rvSectionOrder(scoped.filter { it.subscription == sub.name }, sorted),
                    matching,
                    labels,
                    choice.tcpId,
                    searching = typed.isNotBlank(),
                    onPick = {},
                    onDetail = {},
                    rows = RvRowsComposed(0) { Int.MAX_VALUE },
                    nowEpochMs = RvData.nowEpochMs,
                    modifier = Modifier,
                    initiallyOpen = open || picked != null || sub.name in holders,
                    row = { node, label, _, order ->
                        SectionRow(node, label, order, choice, mark = lead(choice, node.id, score)) { if (!score) choice = choice.tapped(it, apply) }
                    },
                )
            }
        }
        if (matching.isEmpty()) {
            RvGutter { RvEmptyCaption(sampleText("找不到符合的節點", "找不到符合的节点", "No matching nodes"), stringResource(R.string.rv_conn_none_line)) }
        }
    }
}

/** The line under the title: how the group picks and how many nodes it has. A Score group is named as the templates name it. */
@Composable
private fun Caption(group: RvPolicyGroup, score: Boolean) {
    val count = pluralStringResource(R.plurals.rv_policy_node_count, group.members.size, group.members.size)
    if (score) RvCaption(stringResource(R.string.policy_kind_score) + SEPARATOR + count, R.drawable.rv_sym_bolt) else RvCaption(stringResource(group.kind.label) + SEPARATOR + count, group.kind.glyph)
}

/** A node of a section: the picker's row, its name's emoji drawn as our icons, [mark] before its line. */
@Composable
private fun SectionRow(node: RvNode, label: String, order: Int, choice: RvApplyChoice, mark: String?, onPick: (String) -> Unit) {
    val shown = rvShownName(node.name)
    RvNodePickRow(
        node,
        label,
        choice.chosen(node.id),
        order,
        onPick,
        onDetail = {},
        title = shown.text.ifEmpty { rvMarkName(shown.mark, label) },
        lead = mark,
        mark = { current -> RvNameDisc(shown.mark, node.place.flag, current, RvNodesSize.rowFlag) },
    )
}

/**
 * What a row chosen on a network says before its line: with the switch off nothing (one node serves both; in a Score group
 * Score's mark); on, the
 * networks that chose it (TCP, UDP, or TCP/UDP for both, q-groupboth), and in a Score group Score's mark: Score for both
 * networks, Score TCP or Score UDP for one (sc3).
 */
@Composable
private fun lead(choice: RvApplyChoice, id: String, score: Boolean): String? {
    if (!choice.apart) return "Score".takeIf { score && id == choice.tcpId }
    val tcp = id == choice.tcpId
    val udp = id == choice.udpId
    val networks = listOfNotNull(stringResource(R.string.ui_tcp).takeIf { tcp }, stringResource(R.string.ui_udp).takeIf { udp }).joinToString("/")
    return when {
        networks.isEmpty() -> null
        score -> if (tcp && udp) "Score" else "Score $networks"
        else -> networks
    }
}
