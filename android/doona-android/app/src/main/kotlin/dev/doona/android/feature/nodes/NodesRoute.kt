package dev.doona.android.feature.nodes

import android.content.ClipData
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.doona.android.R
import dev.doona.android.designsystem.rv.LocalRvTesting
import dev.doona.android.designsystem.rv.LocalRvUpdating
import dev.doona.android.designsystem.rv.RvNotice
import dev.doona.android.designsystem.rv.RvNoticeKind
import dev.doona.android.designsystem.rv.RvPullDown
import dev.doona.android.designsystem.rv.RvPullLook
import dev.doona.android.designsystem.rv.RvPullMotion
import dev.doona.android.designsystem.rv.RvPullState
import dev.doona.android.engine.Network
import dev.doona.android.feature.messageRes
import dev.doona.android.feature.model.RvNodeSort
import dev.doona.android.feature.nodes.ui.LocalRvRowActions
import dev.doona.android.feature.nodes.ui.RvApplyTo
import dev.doona.android.feature.nodes.ui.RvRowActions
import kotlinx.coroutines.launch

/*
 * The 節點 pages on the one [NodesViewModel] that 活動's entry holds (plan 2.2): the routing builds it there with
 * `NodesViewModel.factory(engine)` and hands the same instance to the pull-down, 節點, a node's page, search and the main
 * node's picker, so each reads the catalog mapped once.
 */

/** 節點: the map, the fastest nodes and every node by part of the world. Before the catalog is read, the page's skeleton. */
@Composable
internal fun NodesRoute(viewModel: NodesViewModel, onBack: () -> Unit, onOpen: (String) -> Unit, onAllNodes: () -> Unit, onAdd: () -> Unit) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val page = remember(ui.state) { RvNodesPageState(ui.state.nodes, ui.state.current, ui.state.subscriptions) }
    NodesLocals(viewModel, ui) {
        RvNodesPage(
            page,
            onBack,
            onOpen = onOpen,
            onAllNodes = onAllNodes,
            onAdd = onAdd,
            score = ui.score,
            caption = mainCaption(ui),
            banner = ui.failure?.let { nodesBanner(it, viewModel::retry) },
            loading = !ui.loaded,
        )
    }
}

/** A node's page: its tests with their times, 使用 and 測試延遲. A node gone from the catalog leaves the page. */
@Composable
internal fun NodeRoute(viewModel: NodesViewModel, nodeId: String, onBack: () -> Unit) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    if (!ui.loaded) return RvNodeDetailSkeleton(onBack)
    val node = ui.state.node(nodeId) ?: return LaunchedEffect(Unit) { onBack() }
    NodesLocals(viewModel, ui) {
        RvNodeDetail(
            node,
            current = node.id in ui.state.current,
            testedAtEpochMs = ui.state.testedAt[node.id].orEmpty(),
            onBack = onBack,
            onUse = { viewModel.pick(node.id) },
            onTest = viewModel::test,
            banner = ui.failure?.let { nodesBanner(it, viewModel::retry) },
            score = ui.score,
            canUse = ui.state.mainGroupId != null,
        )
    }
}

/**
 * The search page (brief rv-gestures G7): the picker with its field focused; a pick makes the node the main one and,
 * once the core has switched, closes. A failed pick leaves the page open under its banner.
 */
@Composable
internal fun NodeSearchRoute(viewModel: NodesViewModel, onClose: () -> Unit, onDetail: (String) -> Unit, onAdd: () -> Unit) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var applyTo by rememberSaveable { mutableStateOf(RvApplyTo.Both) }
    val apply = applyTo.takeIf { ui.state.split }
    NodesLocals(viewModel, ui) {
        RvNodePicker(
            ui.state.nodes,
            ui.state.subscriptions,
            ui.state.currentId,
            ui.nowEpochMs,
            onPick = { viewModel.pick(it, apply?.network(), onClose) },
            onDetail = onDetail,
            onClose = onClose,
            searchPage = true,
            banner = ui.failure?.let { nodesBanner(it, viewModel::retry) },
            caption = mainCaption(ui),
            alsoCurrent = ui.state.current,
            apply = apply,
            onApply = { applyTo = it },
            onAdd = onAdd,
            loading = !ui.loaded,
        )
    }
}

/**
 * The main node's picker, pushed from 規則's 主節點 card: a tap picks the node and the page stays. The order and 套用到
 * are kept for the page's life on the back stack; a failed pick or test shows its banner.
 */
@Composable
internal fun MainNodeRoute(viewModel: NodesViewModel, onBack: () -> Unit, onSearch: () -> Unit, onAdd: () -> Unit) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var sort by rememberSaveable { mutableStateOf(RvNodeSort.Latency) }
    var applyTo by rememberSaveable { mutableStateOf(RvApplyTo.Both) }
    val apply = applyTo.takeIf { ui.state.split }
    NodesLocals(viewModel, ui) {
        RvNodePickerPage(
            ui.state.nodes,
            ui.state.subscriptions,
            ui.state.currentId,
            sort,
            { sort = it },
            onPick = { viewModel.pick(it, apply?.network()) },
            onBack = onBack,
            onTest = viewModel::test,
            onSearch = onSearch,
            nowEpochMs = ui.nowEpochMs,
            onAdd = onAdd,
            caption = mainCaption(ui),
            banner = ui.failure?.let { nodesBanner(it, viewModel::retry) },
            alsoCurrent = ui.state.current,
            apply = apply,
            onApply = { applyTo = it },
            loading = !ui.loaded,
        )
    }
}

/**
 * 活動 with the node picker it pulls down (ARCH D30), hosted inside 活動's entry and not on the back stack, so 活動 keeps
 * its state while the picker covers it. A pick switches the main node and, once the core has switched, closes the picker;
 * a failed pick leaves it open under its banner. A long press or the row's info mark opens the node's page ([onDetail]);
 * with no node, the empty state's 新增訂閱 runs [onAdd]. [activity] is 活動's route, given the pull's look.
 */
@Composable
internal fun NodesPull(
    viewModel: NodesViewModel,
    variant: RvPullMotion,
    state: RvPullState,
    onDetail: (String) -> Unit,
    onAdd: () -> Unit,
    activity: @Composable (RvPullLook) -> Unit,
) {
    RvPullDown(variant, state, activity) { motion ->
        val ui by viewModel.ui.collectAsStateWithLifecycle()
        var applyTo by rememberSaveable { mutableStateOf(RvApplyTo.Both) }
        val apply = applyTo.takeIf { ui.state.split }
        NodesLocals(viewModel, ui, shown = state.drawn) {
            RvNodePicker(
                ui.state.nodes,
                ui.state.subscriptions,
                ui.state.currentId,
                ui.nowEpochMs,
                onPick = { viewModel.pick(it, apply?.network(), motion.close) },
                onDetail = onDetail,
                onClose = motion.close,
                section = motion.section,
                stretch = motion.stretch,
                banner = ui.failure?.let { nodesBanner(it, viewModel::retry) },
                caption = mainCaption(ui),
                alsoCurrent = ui.state.current,
                apply = apply,
                onApply = { applyTo = it },
                onAdd = onAdd,
                loading = !ui.loaded,
            )
        }
    }
}

/**
 * What every 節點 route gives its page: the test running ([LocalRvTesting]), the subscriptions updating
 * ([LocalRvUpdating]) and the rows' menus' actions ([LocalRvRowActions]) on [viewModel]: 設為主節點, 測試延遲, 複製分享連結
 * (the core's link to the clipboard), and a subscription's 更新 and 刪除. A pull-down picker not [shown] follows no test or
 * update, as [RvPullDown] keeps it (a busy bar would run in each row).
 */
@Composable
private fun NodesLocals(viewModel: NodesViewModel, ui: NodesUi, shown: Boolean = true, content: @Composable () -> Unit) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val actions = remember(viewModel, clipboard, scope) {
        RvRowActions(
            useNode = { viewModel.pick(it) },
            testNode = viewModel::testNode,
            updateSubscription = viewModel::update,
            deleteSubscription = viewModel::remove,
            copyLink = { node ->
                viewModel.copyLink(node.id) { link -> scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(node.name, link))) } }
            },
        )
    }
    CompositionLocalProvider(
        LocalRvTesting provides (shown && ui.testing),
        LocalRvUpdating provides if (shown) ui.updating else emptySet(),
        LocalRvRowActions provides actions,
        content = content,
    )
}

/**
 * The line under the title: what a pick does. With no main group (a hand-edited profile may have none) a pick makes no
 * node the main one, so there is no line, as 活動 shows its main node as a dash (q-p3a-nomain).
 */
@Composable
private fun mainCaption(ui: NodesUi): String? =
    if (ui.loaded && ui.state.mainGroupId == null) null else stringResource(R.string.rv_main_node_hint)

/** The banner of a failed action: what failed over the engine's reason, and 重試 ([onRetry]), as the group page's (pv-d M8). */
@Composable
private fun nodesBanner(failure: NodesFailure, onRetry: () -> Unit) = RvNotice(
    RvNoticeKind.Error,
    if (failure.action == NodesAction.Update) {
        stringResource(R.string.rv_sub_failed_title, failure.subject.orEmpty())
    } else {
        stringResource(failure.action.title)
    },
    stringResource(failure.code.messageRes()),
    stringResource(R.string.rv_retry),
    onRetry,
)

@get:StringRes
private val NodesAction.title: Int
    get() = when (this) {
        NodesAction.Select -> R.string.rv_failed_switch_node
        NodesAction.Test -> R.string.rv_failed_test_latency
        NodesAction.Read -> R.string.rv_failed_read_nodes
        NodesAction.Share -> R.string.rv_failed_copy_link
        NodesAction.Update -> R.string.rv_sub_failed_title
        NodesAction.Remove -> R.string.rv_failed_remove_subscription
    }

/** The engine's network for a 套用到 choice; null for both. */
private fun RvApplyTo.network(): Network? = when (this) {
    RvApplyTo.Both -> null
    RvApplyTo.Tcp -> Network.Tcp
    RvApplyTo.Udp -> Network.Udp
}
