package dev.doona.android.feature.nodes.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RvChipPick
import dev.doona.android.designsystem.rv.RvMenuItem
import dev.doona.android.feature.model.RV_INLINE
import dev.doona.android.feature.model.RvNode
import dev.doona.android.feature.model.RvNodeSort
import dev.doona.android.feature.model.RvSubscription

/**
 * The 訂閱 and 排序 chips of every node list (節點, the pull-down picker and search, a group's page; decision 2026-11-25
 * 19:25): the subscription at the start, the order at the end, each opening a menu of its choices. [subscription] is the
 * picked subscription's id ([rvPicked]), null for all of them, and [RV_INLINE] for the nodes of no subscription. The
 * menu offers the subscriptions that gave [nodes] a node, and 組態內 when the profile lists any itself: one whose fetch
 * gave none would show an empty page. The menus' glyphs are the library's nearest (it has no sort glyphs, s1125d:
 * 排序選單圖示暫代).
 */
@Composable
internal fun rvNodeChips(
    subscriptions: List<RvSubscription>,
    nodes: List<RvNode>,
    subscription: String?,
    order: RvNodeSort,
    onSubscription: (String?) -> Unit,
    onOrder: (RvNodeSort) -> Unit,
    // honk-gap:node-history
    comparable: Boolean = nodes.count { inPicked(it, subscription) && it.answered >= 2 } >= 2,
): Pair<RvChipPick, RvChipPick> {
    val all = stringResource(R.string.rv_nodes_all_subscriptions)
    val ours = stringResource(R.string.rv_nodes_inline)
    val given = nodes.mapTo(HashSet()) { it.subscriptionId }
    val subs = listOf(RvMenuItem(all, R.drawable.rv_sym_rss_feed) { onSubscription(null) }) +
        subscriptions.filter { it.id in given }.map { sub -> RvMenuItem(sub.name, R.drawable.rv_sym_rss_feed) { onSubscription(sub.id) } } +
        listOfNotNull(RvMenuItem(ours, R.drawable.rv_sym_link) { onSubscription(RV_INLINE) }.takeIf { RV_INLINE in given })
    if (!comparable && order == RvNodeSort.Stability) LaunchedEffect(Unit) { onOrder(RvNodeSort.Latency) }
    val orders = RvNodeSort.entries.map { sort -> RvMenuItem(stringResource(sort.label), sort.glyph(), enabled = comparable || sort != RvNodeSort.Stability) { onOrder(sort) } }
    val picked = when (subscription) {
        null -> all
        RV_INLINE -> ours
        else -> subscriptions.firstOrNull { it.id == subscription }?.name ?: all
    }
    return RvChipPick(picked, items = subs) to RvChipPick(stringResource(order.chipLabel()), items = orders)
}

/**
 * The chips' pick as it stands with [nodes]: [picked] while one of [nodes] is in it, else null (every subscription), so a
 * subscription that lost its nodes, or 組態內 with none left, never leaves an empty list behind.
 */
internal fun rvPicked(picked: String?, nodes: List<RvNode>): String? = picked?.takeIf { id -> nodes.any { it.subscriptionId == id } }

/** Whether [node] is in the chips' [subscription] (an id): every node for null, the nodes of no subscription for [RV_INLINE]. */
internal fun inPicked(node: RvNode, subscription: String?): Boolean = subscription == null || node.subscriptionId == subscription

/** The order chip's label: 依延遲 and its kin. */
private fun RvNodeSort.chipLabel(): Int = when (this) {
    RvNodeSort.Latency -> R.string.rv_nodes_by_latency
    RvNodeSort.Stability -> R.string.rv_nodes_by_stability
    RvNodeSort.Name -> R.string.rv_nodes_by_name
    RvNodeSort.Region -> R.string.rv_nodes_by_region
}

private fun RvNodeSort.glyph(): Int = when (this) {
    RvNodeSort.Latency -> R.drawable.rv_sym_speed
    RvNodeSort.Stability -> R.drawable.rv_sym_schedule
    RvNodeSort.Name -> R.drawable.rv_sym_edit
    RvNodeSort.Region -> R.drawable.rv_sym_directions
}
