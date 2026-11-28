package dev.doona.android.feature.nodes.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.res.stringResource
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RvMenuItem
import dev.doona.android.feature.model.RvNode
import dev.doona.android.feature.model.RvSubscription

/**
 * What the rows' long-press menus do (brief rv-gestures G3), given by the app's 節點 routes and by the running sample:
 * [useNode] makes a node the main one, [testNode] tests one node's latency, [copyLink] puts a node's share link on the
 * clipboard (null: no 複製分享連結, unless [noShareLinks]), [updateSubscription] updates one subscription,
 * [deleteSubscription] removes it, [deleteNode] asks to delete a node added by hand (組態內). The default does nothing
 * and offers no menu, as in the goldens and previews. [noShareLinks]: the core keeps no node's share link yet (honk), so
 * every node's menu shows 複製分享連結 disabled (owner, 2026-11-27, keep-1127).
 */
@Immutable
internal class RvRowActions(
    val useNode: ((String) -> Unit)? = null,
    val testNode: ((String) -> Unit)? = null,
    val updateSubscription: ((String) -> Unit)? = null,
    val deleteSubscription: ((String) -> Unit)? = null,
    val copyLink: ((RvNode) -> Unit)? = null,
    val deleteNode: ((String) -> Unit)? = null,
    val noShareLinks: Boolean = false,
)

internal val LocalRvRowActions = staticCompositionLocalOf { RvRowActions() }

/**
 * A node row's menu: 設為主節點 (left out for the main node, or where [use] is null), 測試延遲 (where the page tests one
 * node), 詳情 (its page, through [details]) and 複製分享連結 (the core's link through [RvRowActions.copyLink], to the
 * clipboard; Android shows that it was copied), offered only for a node the core keeps a link for ([RvNode.shareable])
 * and has described ([RvNode.described]), or on every node but disabled where the core keeps none
 * ([RvRowActions.noShareLinks]); then, for a node added by hand (no subscription, 組態內) where the page can delete one,
 * 刪除節點 set apart, its glyph in the error hue, which asks first. [use] is the page's own switch: the picker's closes
 * it as a tap does. A page that neither tests nor deletes offers no menu.
 */
@Composable
internal fun rvNodeMenu(node: RvNode, current: Boolean, use: ((String) -> Unit)?, details: (String) -> Unit): List<RvMenuItem> {
    val actions = LocalRvRowActions.current
    val test = actions.testNode
    val delete = actions.deleteNode?.takeIf { node.subscription.isEmpty() }
    if (test == null && delete == null) return emptyList()
    val setMain = stringResource(R.string.rv_menu_set_main)
    val testLabel = stringResource(R.string.rv_test_latency)
    val detailsLabel = stringResource(R.string.rv_menu_details)
    val copy = stringResource(R.string.rv_menu_copy_link)
    val deleteLabel = stringResource(R.string.rv_node_delete)
    return buildList {
        if (use != null && !current) add(RvMenuItem(setMain, R.drawable.rv_nodes_ic_check_circle) { use(node.id) })
        if (test != null) add(RvMenuItem(testLabel, R.drawable.rv_sym_speed) { test(node.id) })
        add(RvMenuItem(detailsLabel, R.drawable.rv_sym_info) { details(node.id) })
        val copyLink = actions.copyLink
        if (actions.noShareLinks) {
            // honk-gap:node-share
            add(RvMenuItem(copy, R.drawable.rv_sym_ios_share, enabled = false) {})
        } else if (copyLink != null && node.shareable && node.described) {
            add(RvMenuItem(copy, R.drawable.rv_sym_ios_share) { copyLink(node) })
        }
        if (delete != null) add(RvMenuItem(deleteLabel, R.drawable.rv_sym_delete, destructive = true, separated = true) { delete(node.id) })
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
