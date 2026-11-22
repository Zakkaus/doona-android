package dev.doona.android.prototype.rv.policy

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import dev.doona.android.R
import dev.doona.android.designsystem.theme.LocalFrameInsets
import dev.doona.android.prototype.rv.Rv
import dev.doona.android.prototype.rv.RvCard
import dev.doona.android.prototype.rv.RvCardHeader
import dev.doona.android.prototype.rv.RvDelta
import dev.doona.android.prototype.rv.RvFlag
import dev.doona.android.prototype.rv.RvGutter
import dev.doona.android.prototype.rv.RvInline
import dev.doona.android.prototype.rv.RvLargeTitle
import dev.doona.android.prototype.rv.RvRow
import dev.doona.android.prototype.rv.RvScrollPage
import dev.doona.android.prototype.rv.RvSeeAll
import dev.doona.android.prototype.rv.RvSize
import dev.doona.android.prototype.rv.RvSpace
import dev.doona.android.prototype.rv.RvText
import dev.doona.android.prototype.rv.RvTheme
import dev.doona.android.prototype.rv.data.RvNode
import dev.doona.android.prototype.rv.nodes.RvStatusChip
import dev.doona.android.prototype.rv.nodes.SEPARATOR
import dev.doona.android.prototype.rv.nodes.latencyText
import dev.doona.android.prototype.rv.rvSystemScheme

/**
 * A group's page, pushed from its row or the main node card, in the 節點 page's grammar: the back disc and the group's
 * name as the large title; how the group picks and how many nodes it has, and the node it uses in the nodes page's
 * status chip; then its nodes fastest first in 節點's rows, the chosen one badged. In a selector a tap picks the node; a
 * url-test group picks for itself, so its rows do not respond, a note says how it picks and its choice's line begins
 * with 自動.
 * 測試此組 floats at the bottom as node detail's pill.
 */
@Composable
internal fun RvPolicyGroupPage(group: RvPolicyGroup, onBack: () -> Unit, onSelect: (String) -> Unit, onTest: () -> Unit) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val name = stringResource(group.name)
    RvScrollPage(
        name,
        insets,
        onBack = onBack,
        overlay = { RvPolicyBottomAction(stringResource(R.string.rv_policy_test_group), onTest, insets, Modifier.align(Alignment.BottomCenter)) },
    ) { titleBlock ->
        RvLargeTitle(name, titleBlock, insets)
        Header(group)
        RvGutter { NodesCard(group, onSelect) }
        Spacer(Modifier.height(rvPillRoom))
        Spacer(Modifier.windowInsetsBottomHeight(insets))
    }
}

/** The kind and the node count, for url-test the rule it picks by, and the chosen node with its latency. */
@Composable
private fun Header(group: RvPolicyGroup) {
    val count = pluralStringResource(R.plurals.rv_policy_node_count, group.members.size, group.members.size)
    val secondary = Rv.colors.textSecondary
    Column(Modifier.padding(horizontal = RvSpace.gutter)) {
        RvInline(group.kind.glyph, stringResource(group.kind.label) + SEPARATOR + count, secondary, Rv.type.rowSub)
        if (group.kind == RvGroupKind.Auto) {
            RvText(stringResource(R.string.rv_policy_auto_note), Rv.type.rowSub, Modifier.padding(top = RvSpace.tight), color = secondary)
        }
        Spacer(Modifier.height(RvSpace.sectionBelow))
        val node = group.selected
        RvStatusChip(R.drawable.rv_nodes_ic_check_circle, stringResource(R.string.rv_policy_selected, node.name, latencyText(node.latencyMs)), Rv.colors.positive)
    }
    Spacer(Modifier.height(RvSpace.titleToCard))
}

/** The nodes, fastest first: the first [ROWS], then 查看全部, which opens the rest in place. */
@Composable
private fun NodesCard(group: RvPolicyGroup, onSelect: (String) -> Unit) {
    var all by rememberSaveable(group.id) { mutableStateOf(false) }
    RvCard {
        RvCardHeader(stringResource(R.string.rv_policy_nodes))
        val nodes = group.byLatency
        for (node in if (all) nodes else nodes.take(ROWS)) NodeRow(group, node, onSelect)
        if (!all && nodes.size > ROWS) RvSeeAll(pluralStringResource(R.plurals.rv_policy_see_all_nodes, nodes.size, nodes.size)) { all = true }
    }
}

/** A node as 節點 lists it; a selector's row picks it, and the chosen row carries the check badge (and 自動 in url-test). */
@Composable
private fun NodeRow(group: RvPolicyGroup, node: RvNode, onSelect: (String) -> Unit) {
    val chosen = node.id == group.selectedId
    val auto = group.kind == RvGroupKind.Auto
    val picked = stringResource(R.string.rv_nodes_in_use)
    val pick = if (auto) {
        if (chosen) Modifier.semantics { stateDescription = picked } else Modifier
    } else {
        Modifier.selectable(chosen, interactionSource = null, indication = ripple(color = Rv.colors.stateLayer), role = Role.RadioButton) { onSelect(node.id) }
    }
    RvRow(
        { RvFlag(node.place.name, RvSize.rowDisc, current = chosen) },
        node.name,
        listOfNotNull(stringResource(R.string.rv_policy_auto).takeIf { chosen && auto }, node.protocol, stringResource(node.place.label)).joinToString(SEPARATOR),
        pick,
        titleEnd = { RvText(latencyText(node.latencyMs), Rv.type.rowValue, align = TextAlign.End) },
        subtitleEnd = node.changeMs?.let { { RvDelta(it) } },
    )
}

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
