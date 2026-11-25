package dev.doona.android.feature.nodes

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.doona.android.R
import dev.doona.android.designsystem.rv.LocalFrameInsets
import dev.doona.android.designsystem.rv.LocalRvTesting
import dev.doona.android.designsystem.rv.RvGutter
import dev.doona.android.designsystem.rv.RvNotice
import dev.doona.android.designsystem.rv.RvNoticeKind
import dev.doona.android.designsystem.rv.RvPageHeader
import dev.doona.android.designsystem.rv.RvPullDown
import dev.doona.android.designsystem.rv.RvPullLook
import dev.doona.android.designsystem.rv.RvPullMotion
import dev.doona.android.designsystem.rv.RvPullState
import dev.doona.android.designsystem.rv.RvScrollPage
import dev.doona.android.designsystem.rv.RvSkeletonCard
import dev.doona.android.designsystem.rv.RvSkeletonEnd
import dev.doona.android.designsystem.rv.RvSkeletonHeader
import dev.doona.android.designsystem.rv.RvSkeletonRow
import dev.doona.android.designsystem.rv.RvSkeletonText
import dev.doona.android.feature.messageRes
import dev.doona.android.feature.model.RvNodeSort

/*
 * The 節點 pages on the one [NodesViewModel] that 活動's entry holds (plan 2.2): the routing builds it there with
 * `NodesViewModel.factory(engine)` and hands the same instance to the pull-down, 節點, a node's page, search and the main
 * node's picker, so each reads the catalog mapped once.
 */

/** 節點: the map, the fastest nodes and every node by part of the world. Before the catalog is read, a skeleton. */
@Composable
internal fun NodesRoute(viewModel: NodesViewModel, onBack: () -> Unit, onOpen: (String) -> Unit, onAllNodes: () -> Unit, onAdd: () -> Unit) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    if (!ui.loaded) return NodesSkeleton(onBack)
    val page = remember(ui.state) { RvNodesPageState(ui.state.nodes, ui.state.currentId, emptyList()) }
    val score = remember(ui.score) { ui.score.takeIf { it.isNotEmpty() }?.let(::RvScoreMarks) }
    CompositionLocalProvider(LocalRvTesting provides ui.testing) {
        // The chips' menus are the group page's (decision 2026-11-25 19:25), not on this base yet: see p3c-STATE.
        RvNodesPage(page, onBack, onFilter = {}, onOpen = onOpen, onAllNodes = onAllNodes, onAdd = onAdd, score = score, caption = mainCaption(ui))
    }
}

/** A node's page: its tests with their times, 使用 and 測試延遲. A node gone from the catalog leaves the page. */
@Composable
internal fun NodeRoute(viewModel: NodesViewModel, nodeId: String, onBack: () -> Unit) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    if (!ui.loaded) return NodesSkeleton(onBack)
    val node = ui.state.nodes.firstOrNull { it.id == nodeId } ?: return LaunchedEffect(Unit) { onBack() }
    val score = remember(ui.score) { ui.score.takeIf { it.isNotEmpty() }?.let(::RvScoreMarks) }
    CompositionLocalProvider(LocalRvTesting provides ui.testing) {
        RvNodeDetail(
            node,
            current = node.id == ui.state.currentId,
            testedAtEpochMs = ui.state.testedAt[node.id].orEmpty(),
            onBack = onBack,
            onUse = { viewModel.pick(node.id) },
            onTest = viewModel::test,
            banner = ui.failure?.let { nodesBanner(it, viewModel::retry) },
            score = score,
            canUse = ui.state.mainGroupId != null,
        )
    }
}

/** The search page (brief rv-gestures G7): the picker with its field focused; a pick makes the node the main one and closes. */
@Composable
internal fun NodeSearchRoute(viewModel: NodesViewModel, onClose: () -> Unit, onDetail: (String) -> Unit) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    CompositionLocalProvider(LocalRvTesting provides ui.testing) {
        RvNodePicker(
            ui.state.nodes,
            ui.state.subscriptions,
            ui.state.currentId,
            ui.nowEpochMs,
            onPick = {
                viewModel.pick(it)
                onClose()
            },
            onDetail = onDetail,
            onClose = onClose,
            searchPage = true,
            banner = ui.failure?.let { nodesBanner(it, viewModel::retry) },
            caption = mainCaption(ui),
        )
    }
}

/**
 * The main node's picker, pushed from 規則's 主節點 card: a tap picks the node and the page stays. The order is kept for
 * the page's life on the back stack.
 */
@Composable
internal fun MainNodeRoute(viewModel: NodesViewModel, onBack: () -> Unit, onSearch: () -> Unit, onAdd: () -> Unit) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var sort by rememberSaveable { mutableStateOf(RvNodeSort.Latency) }
    CompositionLocalProvider(LocalRvTesting provides ui.testing) {
        RvNodePickerPage(
            ui.state.nodes,
            ui.state.subscriptions,
            ui.state.currentId,
            sort,
            { sort = it },
            onPick = { viewModel.pick(it) },
            onBack = onBack,
            onTest = viewModel::test,
            onSearch = onSearch,
            nowEpochMs = ui.nowEpochMs,
            onAdd = onAdd,
            caption = mainCaption(ui),
        )
    }
}

/**
 * 活動 with the node picker it pulls down (ARCH D30), hosted inside 活動's entry and not on the back stack, so 活動 keeps
 * its state while the picker covers it. A pick switches the main node and closes the picker; a long press or the row's
 * info mark opens the node's page ([onDetail]). [activity] is 活動's route, given the pull's look.
 */
@Composable
internal fun NodesPull(
    viewModel: NodesViewModel,
    variant: RvPullMotion,
    state: RvPullState,
    onDetail: (String) -> Unit,
    activity: @Composable (RvPullLook) -> Unit,
) {
    RvPullDown(variant, state, activity) { motion ->
        val ui by viewModel.ui.collectAsStateWithLifecycle()
        CompositionLocalProvider(LocalRvTesting provides ui.testing) {
            RvNodePicker(
                ui.state.nodes,
                ui.state.subscriptions,
                ui.state.currentId,
                ui.nowEpochMs,
                onPick = {
                    viewModel.pick(it)
                    motion.close()
                },
                onDetail = onDetail,
                onClose = motion.close,
                section = motion.section,
                stretch = motion.stretch,
                banner = ui.failure?.let { nodesBanner(it, viewModel::retry) },
                caption = mainCaption(ui),
            )
        }
    }
}

/** The line under the title: what a pick does, or, when the profile has no main group, that there is no main node. */
@Composable
private fun mainCaption(ui: NodesUi): String =
    stringResource(if (ui.loaded && ui.state.mainGroupId == null) R.string.rv_no_main_group else R.string.rv_main_node_hint)

/** 節點 before the catalog is read, as after process death: the title and rows of nodes. */
@Composable
private fun NodesSkeleton(onBack: () -> Unit) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val title = stringResource(R.string.rv_nodes_title)
    RvScrollPage(title, insets, onBack = onBack) { titleBlock ->
        RvPageHeader(title, titleBlock, insets)
        RvGutter {
            RvSkeletonCard {
                RvSkeletonHeader(stringResource(R.string.rv_nodes_in_use))
                RvSkeletonRow(0, RvSkeletonText.Mixed, RvSkeletonEnd.Latency)
            }
            RvSkeletonCard { repeat(SKELETON_ROWS) { RvSkeletonRow(it + 1, RvSkeletonText.Mixed, RvSkeletonEnd.Latency) } }
        }
    }
}

/** The banner of a failed action: what failed over the engine's reason, and 重試 ([onRetry]), as the group page's (pv-d M8). */
@Composable
private fun nodesBanner(failure: NodesFailure, onRetry: () -> Unit) = RvNotice(
    RvNoticeKind.Error,
    stringResource(failure.action.title),
    stringResource(failure.code.messageRes()),
    stringResource(R.string.rv_retry),
    onRetry,
)

@get:StringRes
private val NodesAction.title: Int
    get() = when (this) {
        NodesAction.Select -> R.string.rv_failed_switch_node
        NodesAction.Test -> R.string.rv_failed_test_latency
    }

private const val SKELETON_ROWS = 6
