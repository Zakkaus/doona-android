package dev.doona.android.feature.policies

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RvChipPick
import dev.doona.android.designsystem.rv.RvMenuItem
import dev.doona.android.designsystem.rv.RvPlace
import dev.doona.android.feature.model.RV_INLINE_KEY
import dev.doona.android.feature.model.RvNode
import dev.doona.android.feature.model.RvNodeSort
import dev.doona.android.feature.model.RvSubscription
import dev.doona.android.feature.model.rvByLatency
import dev.doona.android.feature.model.rvByName
import dev.doona.android.feature.model.rvSteadiness

/**
 * The group page's two chips, as the 節點 page has them: the source at the start, the order at the end. Each opens a menu
 * of its choices; [source] is the picked subscription's id, [RV_INLINE_KEY] for the nodes added by hand (offered when
 * [inline]), null for all of them. The menus' glyphs are the library's nearest (it has no sort glyphs, s1125d:
 * 排序選單圖示暫代): the subscription's feed mark, 組態內's link, and one per order.
 */
@Composable
internal fun rvGroupChips(
    subscriptions: List<RvSubscription>,
    inline: Boolean,
    source: String?,
    order: RvNodeSort,
    onSource: (String?) -> Unit,
    onOrder: (RvNodeSort) -> Unit,
): Pair<RvChipPick, RvChipPick> {
    val all = stringResource(R.string.rv_nodes_all_subscriptions)
    val byHand = stringResource(R.string.rv_nodes_inline)
    val subs = listOf(RvMenuItem(all, R.drawable.rv_sym_rss_feed) { onSource(null) }) +
        subscriptions.map { sub -> RvMenuItem(sub.name, R.drawable.rv_sym_rss_feed) { onSource(sub.id) } } +
        listOfNotNull(RvMenuItem(byHand, R.drawable.rv_sym_link) { onSource(RV_INLINE_KEY) }.takeIf { inline })
    val label = when (source) {
        null -> all
        RV_INLINE_KEY -> byHand
        else -> subscriptions.firstOrNull { it.id == source }?.name ?: all
    }
    val orders = RvNodeSort.entries.map { sort -> RvMenuItem(stringResource(sort.label), sort.glyph()) { onOrder(sort) } }
    return RvChipPick(label, items = subs) to RvChipPick(stringResource(order.chipLabel()), items = orders)
}

/** The order chip's label: 依延遲 and its kin. */
private fun RvNodeSort.chipLabel(): Int = when (this) {
    RvNodeSort.Latency -> R.string.rv_nodes_by_latency
    RvNodeSort.Stability -> R.string.rv_group_by_stability
    RvNodeSort.Name -> R.string.rv_group_by_name
    RvNodeSort.Region -> R.string.rv_group_by_region
}

private fun RvNodeSort.glyph(): Int = when (this) {
    RvNodeSort.Latency -> R.drawable.rv_sym_speed
    RvNodeSort.Stability -> R.drawable.rv_sym_schedule
    RvNodeSort.Name -> R.drawable.rv_sym_edit
    RvNodeSort.Region -> R.drawable.rv_sym_directions
}

/**
 * A section's rows in [order]: fastest by the last test, steadiest, by the name as the row draws it (a name's emoji is
 * left out, so 🇭🇰 Hong Kong sorts under H), or by place (the country with the fastest node first, 其他 last, as the 節點
 * page's cards are). Nodes that never answered come after the ones that did, as in the picker.
 */
internal fun rvSectionOrder(nodes: List<RvNode>, order: RvNodeSort): List<RvNode> = when (order) {
    RvNodeSort.Latency -> nodes.sortedWith(rvByLatency)
    RvNodeSort.Stability -> nodes.sortedWith(rvSteadiness)
    RvNodeSort.Name -> nodes.sortedWith(rvByName)
    RvNodeSort.Region -> nodes.sortedWith(rvByLatency).groupBy { it.place }.entries.sortedBy { it.key == RvPlace.Other }.flatMap { it.value }
}
