package dev.doona.android.feature.nodes

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.doona.android.R
import dev.doona.android.designsystem.rv.LocalFrameInsets
import dev.doona.android.designsystem.rv.RvAddEntry
import dev.doona.android.designsystem.rv.RvBannerSlot
import dev.doona.android.designsystem.rv.RvCaption
import dev.doona.android.designsystem.rv.RvEmptyState
import dev.doona.android.designsystem.rv.RvGlassButton
import dev.doona.android.designsystem.rv.RvGutter
import dev.doona.android.designsystem.rv.RvNotice
import dev.doona.android.designsystem.rv.RvPageHeader
import dev.doona.android.designsystem.rv.RvScrollPage
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.feature.model.RvNode
import dev.doona.android.feature.model.RvNodeSort
import dev.doona.android.feature.model.RvSubscription
import dev.doona.android.feature.model.rvNodeGroups
import dev.doona.android.feature.nodes.ui.RvApplyCard
import dev.doona.android.feature.nodes.ui.RvApplyTo
import dev.doona.android.feature.nodes.ui.RvNodeGroups
import dev.doona.android.feature.nodes.ui.RvNodePickerControls
import dev.doona.android.feature.nodes.ui.RvNodeSectionSkeleton
import dev.doona.android.feature.nodes.ui.inPicked
import dev.doona.android.feature.nodes.ui.rememberRvNodeTitles
import dev.doona.android.feature.nodes.ui.rvNodeChips
import dev.doona.android.feature.nodes.ui.rvPicked

/**
 * The main node's picker, pushed from 規則's 主節點 card: the back disc and 測試延遲 in the bar, 主節點 as the large title
 * over what the choice does, the search pill and every node picker's 訂閱 and 排序 chips (ruling q-p3c-mainchips), then
 * the node list, the 訂閱 chip's pick only. A tap picks the node and stays, the
 * check moving to it. The order is the caller's ([sort], [onSort]), so the page keeps its last choice across visits;
 * the search pill opens the search page ([onSearch]).
 * With no node (no subscription) the order has nothing to order and the list gives way to the empty state, whose 新增訂閱
 * runs [onAdd]. A subscription's end date reads as of [nowEpochMs]. A failed pick or test shows its [banner] under the
 * title. While the main group's networks are apart ([apply] not null) 套用到 stands over the list (decision 2026-11-25
 * 14:37) and the node of each network is checked ([currentId], [alsoCurrent]).
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
    caption: String? = stringResource(R.string.rv_main_node_hint),
    banner: RvNotice? = null,
    alsoCurrent: Set<String> = emptySet(),
    score: RvScoreMarks? = null,
    apply: RvApplyTo? = null,
    onApply: (RvApplyTo) -> Unit = {},
    loading: Boolean = false,
    onAddNode: (() -> Unit)?,
) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val title = stringResource(R.string.rv_main_node)
    val test = stringResource(R.string.rv_test_latency)
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    val picked = rvPicked(chosen, nodes)
    val titles = rememberRvNodeTitles()
    val groups = remember(nodes, subscriptions, sort, picked, titles) { rvNodeGroups(nodes.filter { inPicked(it, picked) }, subscriptions, sort, titles) }
    RvScrollPage(title, insets, onBack = onBack, actions = { glass -> RvGlassButton(glass, R.drawable.rv_sym_speed, test, onTest) }) { titleBlock ->
        // Before the catalog is read the controls stand (their labels are known) over the list's skeleton.
        val empty = !loading && nodes.isEmpty()
        val controls: (@Composable () -> Unit)? = if (empty || (loading && banner != null)) {
            null
        } else {
            {
                val (subChip, orderChip) = rvNodeChips(subscriptions, nodes, picked, sort, { chosen = it }, onSort)
                RvNodePickerControls(subChip, orderChip, onSearch)
            }
        }
        RvPageHeader(title, titleBlock, insets, caption = caption?.let { line -> { RvCaption(line) } }, control = controls)
        RvBannerSlot(banner)
        if (loading && banner != null) return@RvScrollPage
        if (loading) {
            RvNodeSectionSkeleton()
        } else if (empty) {
            RvGutter { RvNoSubscription(onAdd, onAddNode) }
        } else {
            if (apply != null) {
                RvGutter { RvApplyCard(apply, onApply) }
                Spacer(Modifier.height(RvSpace.cardGap))
            }
            RvNodeGroups(groups, currentId, sort, nowEpochMs, alsoCurrent, score, onPick)
        }
    }
}

/** The node lists' empty state: no subscription yet, and 新增訂閱 ([onAdd]). One for the main node's page and the pickers. */
@Composable
internal fun RvNoSubscription(onAdd: () -> Unit, onAddNode: (() -> Unit)?) = RvEmptyState(
    stringResource(R.string.rv_empty_subscription),
    stringResource(R.string.rv_empty_subscription_line),
    stringResource(R.string.rv_add_subscription),
    onAdd,
    RvAddEntry(stringResource(R.string.rv_addnode), onAddNode),
)
