package dev.doona.android.prototype.rv

import android.content.ClipData
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
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

/** The subscriptions being updated: their rows show [RvBusy] where what is left would be. */
internal val LocalRvUpdating = compositionLocalOf { emptySet<String>() }

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
        if (use != null && !current) add(RvMenuItem(R.drawable.rv_nodes_ic_check_circle, setMain) { use(node.id) })
        add(RvMenuItem(R.drawable.rv_sym_speed, testLabel) { test(node.id) })
        add(RvMenuItem(R.drawable.rv_sym_info, detailsLabel) { details(node.id) })
        add(RvMenuItem(R.drawable.rv_sym_ios_share, copy) { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(node.name, node.shareLink()))) } })
    }
}

/**
 * A subscription row's menu: 更新, and 刪除 after its question. 編輯 is left out: the RV set has no subscription editor
 * to open (a gap written in the lane's STATE).
 */
@Composable
internal fun rvSubscriptionMenu(sub: RvSubscription): List<RvMenuItem> {
    val actions = LocalRvRowActions.current
    val update = actions.updateSubscription ?: return emptyList()
    val updateLabel = stringResource(R.string.rv_menu_update)
    val deleteLabel = stringResource(R.string.rv_menu_delete)
    val question = stringResource(R.string.rv_menu_delete_question, sub.name)
    return buildList {
        add(RvMenuItem(R.drawable.rv_stats_ic_sync, updateLabel) { update(sub.id) })
        actions.deleteSubscription?.let { delete -> add(RvMenuItem(R.drawable.rv_sym_delete, deleteLabel, question) { delete(sub.id) }) }
    }
}
