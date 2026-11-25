package dev.doona.android.prototype.rv.nodes

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
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
import dev.doona.android.designsystem.rv.RvDelta
import dev.doona.android.designsystem.rv.RvEmptyState
import dev.doona.android.designsystem.rv.RvFlag
import dev.doona.android.designsystem.rv.RvGlassButton
import dev.doona.android.designsystem.rv.RvGutter
import dev.doona.android.designsystem.rv.RvLatencyChange
import dev.doona.android.designsystem.rv.RvLatencyText
import dev.doona.android.designsystem.rv.RvNodesSize
import dev.doona.android.designsystem.rv.RvPageHeader
import dev.doona.android.designsystem.rv.RvPlace
import dev.doona.android.designsystem.rv.RvRangeRow
import dev.doona.android.designsystem.rv.RvRow
import dev.doona.android.designsystem.rv.RvScrollPage
import dev.doona.android.designsystem.rv.RvSearchPill
import dev.doona.android.designsystem.rv.RvSeeAll
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.designsystem.rv.RvText
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.designsystem.rv.SEPARATOR
import dev.doona.android.designsystem.rv.rvRaisedReach
import dev.doona.android.designsystem.rv.rvRowSelect
import dev.doona.android.designsystem.rv.rvSystemScheme
import dev.doona.android.feature.model.RvNode
import dev.doona.android.feature.model.RvNodeGroup
import dev.doona.android.feature.model.RvNodeSort
import dev.doona.android.feature.model.RvSubscription
import dev.doona.android.feature.model.rvNodeGroups
import dev.doona.android.prototype.rv.RvSubscriptionRow
import dev.doona.android.prototype.rv.data.RvData

/**
 * The node list's control row: the search pill, which opens the search page ([onSearch]), unless that is null, [RvSpace.searchGap]
 * over the order as a row of Revolut's range chips, every key in view and the active one raised. Chips rather than a filter chip and its menu: a
 * filter chip's chevron promises a menu, which this library does not have, and four short keys fit on one row, so the
 * choice and the active key are one tap and one glance. A page whose bar already searches the nodes (策略's 全域) leaves
 * the pill out: one search per page (rule 9); the main node's pushed page has none in its bar, so its list keeps it.
 */
@Composable
internal fun RvNodeListControls(sort: RvNodeSort, onSort: (RvNodeSort) -> Unit, onSearch: (() -> Unit)?) {
    Column {
        if (onSearch != null) {
            RvSearchPill(onSearch, Modifier.fillMaxWidth())
            // What it searches starts one search gap under it, as under every search (RvSearchSlot); here, the order.
            Spacer(Modifier.height(RvSpace.searchGap))
        }
        // The control row's gap below measures from the selected chip's shadow, the edge the eye sees.
        val sorts = RvNodeSort.entries
        RvRangeRow(sorts.map { stringResource(it.label) }, sorts.indexOf(sort), { onSort(sorts[it]) }, Modifier.padding(bottom = rvRaisedReach))
    }
}

/**
 * The node list (the picker under 全域 and the main node's page, rv-final's pull-down picker): one card per group, its
 * head row over its nodes in 節點's rows, the [currentId] node's flag checked. By default a group is a subscription,
 * headed by its 訂閱 row (use against quota, end date, what is left); under 地區 it is a country, headed by the same row
 * with the country's flag, its name and how many nodes it has. Each card shows its first [ROWS] and the current node
 * wherever it falls, then 查看全部 opens the rest in place. A tap on a node picks it ([onPick]), and the check moves.
 * Under 穩定 a row's second trailing line is how many of its kept tests answered, where its change is otherwise. While a
 * latency test of every node, or of one node, runs ([LocalRvTesting], [LocalRvTestingNodes]) a busy bar stands at the
 * row's end in the room of the latency and what is under it, as in the pull-down picker ([RvLatencyChange]): the name
 * keeps its width and lines.
 */
@Composable
internal fun RvNodeGroups(groups: List<RvNodeGroup>, currentId: String, sort: RvNodeSort, nowEpochMs: Long, onPick: (String) -> Unit) {
    RvGutter {
        groups.forEach { key(it.key) { GroupCard(it, currentId, sort, nowEpochMs, onPick) } }
    }
}

@Composable
private fun GroupCard(group: RvNodeGroup, currentId: String, sort: RvNodeSort, nowEpochMs: Long, onPick: (String) -> Unit) {
    var all by rememberSaveable(group.key) { mutableStateOf(false) }
    val shown = if (all) group.nodes else group.nodes.take(ROWS) + group.nodes.drop(ROWS).filter { it.id == currentId }
    val count = group.nodes.size
    RvCard {
        when (group) {
            is RvNodeGroup.Subscription -> RvSubscriptionRow(group.subscription, nowEpochMs)
            is RvNodeGroup.Place -> PlaceRow(group.place, count)
        }
        shown.forEach { key(it.id) { NodeRow(it, it.id == currentId, sort, onPick) } }
        if (shown.size < count) RvSeeAll(pluralStringResource(R.plurals.rv_nodes_see_all, count, count)) { all = true }
    }
}

/** A country's head row, laid out as a subscription's: its flag, its name, and how many nodes it has. */
@Composable
private fun PlaceRow(place: RvPlace, count: Int) {
    RvRow({ RvFlag(place.flag, RvNodesSize.rowFlag) }, stringResource(place.label), pluralStringResource(R.plurals.rv_nodes_nodes, count, count))
}

/**
 * 節點's row as a choice: the flag (checked when [current]), name, protocol and place, latency over its change, or under
 * 穩定 over how many of its tests answered.
 */
@Composable
private fun NodeRow(node: RvNode, current: Boolean, sort: RvNodeSort, onPick: (String) -> Unit) {
    val inUse = stringResource(R.string.rv_nodes_in_use)
    val pick = Modifier.rvRowSelect(current, { onPick(node.id) })
    val steady = stringResource(R.string.rv_nodes_stability, node.answered, node.history.size)
    val testing = LocalRvTesting.current || node.id in LocalRvTestingNodes.current
    val second: (@Composable () -> Unit)? = if (sort == RvNodeSort.Stability) {
        { RvText(steady, Rv.type.rowSub, color = Rv.colors.textSecondary, align = TextAlign.End) }
    } else {
        node.changeMs?.let { change -> { RvDelta(change) } }
    }
    RvRow(
        { RvFlag(node.place.flag, RvNodesSize.rowFlag, current = current) },
        node.name,
        node.protocol + SEPARATOR + stringResource(node.place.label),
        if (current) pick.semantics { stateDescription = inUse } else pick,
        titleEnd = { RvLatencyText(node.latencyMs, node.timedOut) },
        subtitleEnd = second,
        endBusy = testing,
        titleKeepWords = false,
    )
}

/** As many rows as a policy group's page shows before its 查看全部: the list is one to pick from. */
private const val ROWS = 8

/**
 * The main node's picker, pushed from 規則's 主節點 card: the back disc and 測試延遲 in the bar, 主節點 as the large title
 * over what the choice does, the search pill and the order, then the node list. A tap picks the node and stays, the
 * check moving to it. The order is the caller's ([sort], [onSort]), so the page keeps its last choice across visits;
 * the search pill opens the search page ([onSearch]).
 * With no node (no subscription) the order has nothing to order and the list gives way to the empty state, whose 新增訂閱
 * runs [onAdd]. A subscription's end date reads as of [nowEpochMs].
 */
@Composable
internal fun RvNodePickerPage(
    nodes: List<RvNode>,
    subscriptions: List<RvSubscription>,
    currentId: String,
    sort: RvNodeSort,
    onSort: (RvNodeSort) -> Unit,
    onPick: (String) -> Unit,
    onBack: () -> Unit,
    onTest: () -> Unit,
    onSearch: () -> Unit,
    nowEpochMs: Long,
    onAdd: () -> Unit = {},
) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val title = stringResource(R.string.rv_main_node)
    val test = stringResource(R.string.rv_test_latency)
    val groups = remember(nodes, subscriptions, sort) { rvNodeGroups(nodes, subscriptions, sort) }
    RvScrollPage(title, insets, onBack = onBack, actions = { glass -> RvGlassButton(glass, R.drawable.rv_sym_speed, test, onTest) }) { titleBlock ->
        val controls: (@Composable () -> Unit)? = if (nodes.isEmpty()) null else ({ RvNodeListControls(sort, onSort, onSearch) })
        RvPageHeader(title, titleBlock, insets, caption = { RvCaption(stringResource(R.string.rv_main_node_hint)) }, control = controls)
        if (nodes.isEmpty()) {
            RvGutter {
                RvEmptyState(
                    stringResource(R.string.rv_empty_subscription),
                    stringResource(R.string.rv_empty_subscription_line),
                    stringResource(R.string.rv_add_subscription),
                    onAdd,
                )
            }
        } else {
            RvNodeGroups(groups, currentId, sort, nowEpochMs, onPick)
        }
    }
}

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RvNodePickerPagePreview() {
    val dark = isSystemInDarkTheme()
    var current by remember { mutableStateOf(RvData.CURRENT_ID) }
    var sort by remember { mutableStateOf(RvNodeSort.Latency) }
    RvTheme(rvSystemScheme(dark), dark) {
        RvNodePickerPageSample(RvData.nodes, RvData.subscriptions, current, sort, { sort = it }, { current = it }, onBack = {}, onTest = {}, onSearch = {})
    }
}
