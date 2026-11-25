package dev.doona.android.prototype.rv.samples

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RvChipPick
import dev.doona.android.designsystem.rv.RvMenuItem
import dev.doona.android.designsystem.rv.RvPlace
import dev.doona.android.designsystem.rv.rvShownName
import dev.doona.android.feature.model.RvNode
import dev.doona.android.feature.model.RvNodeSort
import dev.doona.android.feature.model.RvSubscription
import dev.doona.android.feature.model.rvByLatency
import dev.doona.android.feature.model.rvSteadiness

/**
 * The group page's two chips, as the 節點 page has them: the subscription at the start, the order at the end. Each opens a
 * menu of its choices; [subscription] is the picked subscription's name, null for all of them. The menus' glyphs are the
 * library's nearest (it has no sort glyphs): the subscription's feed mark, and one per order.
 */
@Composable
internal fun rvGroupChips(
    subscriptions: List<RvSubscription>,
    subscription: String?,
    order: RvNodeSort,
    onSubscription: (String?) -> Unit,
    onOrder: (RvNodeSort) -> Unit,
): Pair<RvChipPick, RvChipPick> {
    val all = stringResource(R.string.rv_nodes_all_subscriptions)
    val subs = listOf(RvMenuItem(all, R.drawable.rv_sym_rss_feed) { onSubscription(null) }) +
        subscriptions.map { sub -> RvMenuItem(sub.name, R.drawable.rv_sym_rss_feed) { onSubscription(sub.name) } }
    val orders = RvNodeSort.entries.map { sort -> RvMenuItem(stringResource(sort.label), sort.glyph()) { onOrder(sort) } }
    return RvChipPick(subscription ?: all, items = subs) to RvChipPick(order.chipLabel(), items = orders)
}

/** The order chip's label: 依延遲 and its kin (the 節點 page's string for latency, the others as its wording). */
@Composable
private fun RvNodeSort.chipLabel(): String = when (this) {
    RvNodeSort.Latency -> stringResource(R.string.rv_nodes_by_latency)
    RvNodeSort.Stability -> sampleText("依穩定", "按稳定", "By stability")
    RvNodeSort.Name -> sampleText("依名稱", "按名称", "By name")
    RvNodeSort.Region -> sampleText("依地區", "按地区", "By region")
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
    RvNodeSort.Name -> nodes.sortedWith(compareBy<RvNode> { it.latencyMs == null }.thenBy(String.CASE_INSENSITIVE_ORDER) { rvShownName(it.name).text.ifEmpty { it.name } })
    RvNodeSort.Region -> nodes.sortedWith(rvByLatency).groupBy { it.place }.entries.sortedBy { it.key == RvPlace.Other }.flatMap { it.value }
}
