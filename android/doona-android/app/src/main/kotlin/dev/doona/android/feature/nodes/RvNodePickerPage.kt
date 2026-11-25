package dev.doona.android.feature.nodes

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import dev.doona.android.R
import dev.doona.android.designsystem.rv.LocalFrameInsets
import dev.doona.android.designsystem.rv.RvCaption
import dev.doona.android.designsystem.rv.RvEmptyState
import dev.doona.android.designsystem.rv.RvGlassButton
import dev.doona.android.designsystem.rv.RvGutter
import dev.doona.android.designsystem.rv.RvPageHeader
import dev.doona.android.designsystem.rv.RvScrollPage
import dev.doona.android.feature.model.RvNode
import dev.doona.android.feature.model.RvNodeSort
import dev.doona.android.feature.model.RvSubscription
import dev.doona.android.feature.model.rvNodeGroups
import dev.doona.android.feature.nodes.ui.RvNodeGroups
import dev.doona.android.feature.nodes.ui.RvNodeListControls

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
