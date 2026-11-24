package dev.doona.android.prototype.rv

import android.content.ClipData
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import dev.doona.android.R
import dev.doona.android.prototype.rv.data.RvNode
import dev.doona.android.prototype.rv.data.RvSubscription
import dev.doona.android.prototype.rv.data.shareLink
import kotlinx.coroutines.launch

/**
 * What the rows' long-press menus do in the running sample (brief rv-gestures G3): [useNode] makes a node the main one,
 * [testNode] tests one node's latency, [updateSubscription] updates one subscription, [deleteSubscription] removes it.
 * The default does nothing and offers no menu, as in the goldens and previews.
 */
@Immutable
internal class RvRowActions(
    val useNode: ((String) -> Unit)? = null,
    val testNode: ((String) -> Unit)? = null,
    val updateSubscription: ((String) -> Unit)? = null,
    val deleteSubscription: ((String) -> Unit)? = null,
)

internal val LocalRvRowActions = staticCompositionLocalOf { RvRowActions() }

/**
 * A node row's menu: 設為主節點 (left out for the main node, or where [use] is null), 測試延遲, 詳情 (its page, through
 * [details]) and 複製分享連結 (the node's [shareLink] to the clipboard; Android shows that it was copied). [use] is the
 * page's own switch: the picker's closes it as a tap does.
 */
@Composable
internal fun rvNodeMenu(node: RvNode, current: Boolean, use: ((String) -> Unit)?, details: (String) -> Unit): List<RvMenuItem> {
    val test = LocalRvRowActions.current.testNode ?: return emptyList()
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val setMain = stringResource(R.string.rv_menu_set_main)
    val testLabel = stringResource(R.string.rv_test_latency)
    val detailsLabel = stringResource(R.string.rv_menu_details)
    val copy = stringResource(R.string.rv_menu_copy_link)
    return buildList {
        if (use != null && !current) add(RvMenuItem(setMain, R.drawable.rv_nodes_ic_check_circle) { use(node.id) })
        add(RvMenuItem(testLabel, R.drawable.rv_sym_speed) { test(node.id) })
        add(RvMenuItem(detailsLabel, R.drawable.rv_sym_info) { details(node.id) })
        add(RvMenuItem(copy, R.drawable.rv_sym_ios_share) { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(node.name, node.shareLink()))) } })
    }
}

/**
 * A subscription row's menu: 更新, and 刪除 set apart, which runs [onDelete] to ask its question (the row's
 * [RvConfirmSheet]) where the sample can delete. 編輯 is left out: the picker has no subscription editor to open.
 */
@Composable
internal fun rvSubscriptionMenu(sub: RvSubscription, onDelete: () -> Unit): List<RvMenuItem> {
    val actions = LocalRvRowActions.current
    val update = actions.updateSubscription ?: return emptyList()
    val updateLabel = stringResource(R.string.rv_menu_update)
    val deleteLabel = stringResource(R.string.rv_menu_delete)
    return buildList {
        add(RvMenuItem(updateLabel, R.drawable.rv_stats_ic_sync) { update(sub.id) })
        if (actions.deleteSubscription != null) add(RvMenuItem(deleteLabel, R.drawable.rv_sym_delete, destructive = true, separated = true, onClick = onDelete))
    }
}
