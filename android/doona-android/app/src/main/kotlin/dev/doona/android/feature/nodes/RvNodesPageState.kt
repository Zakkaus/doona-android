package dev.doona.android.feature.nodes

import androidx.compose.runtime.Immutable
import dev.doona.android.designsystem.rv.RvPlace
import dev.doona.android.designsystem.rv.RvRegion
import dev.doona.android.feature.model.RV_INLINE
import dev.doona.android.feature.model.RvNode
import dev.doona.android.feature.model.RvNodeSort
import dev.doona.android.feature.model.RvSubscription
import dev.doona.android.feature.model.fastest
import dev.doona.android.feature.model.rvByLatency
import dev.doona.android.feature.model.rvSectionOrder
import dev.doona.android.feature.model.steadiest
import dev.doona.android.feature.nodes.ui.inPicked

/**
 * The 節點 page: every node, the ones in use ([current]: the main group's node, or its TCP and its UDP node while their
 * choices differ; none with no main group), and the subscriptions its 訂閱 chip offers.
 */
@Immutable
internal class RvNodesPageState(
    val nodes: List<RvNode>,
    val current: Set<String>,
    val subscriptions: List<RvSubscription> = emptyList(),
) {
    /** The page for the 訂閱 chip's [subscription] (an id, [RV_INLINE] for 組態內): its nodes only, or every node for null. */
    fun scoped(subscription: String?): RvNodesPageState =
        if (subscription == null) this else RvNodesPageState(nodes.filter { inPicked(it, subscription) }, current, subscriptions)

    /** The countries the map marks and the count counts: those with a spot on the map. */
    val places: Set<RvPlace> get() = nodes.mapTo(mutableSetOf()) { it.place }.filterTo(mutableSetOf()) { it.mapped }

    /** The eight fastest nodes by their last test, or the eight steadiest over all tests. */
    fun top(steadiest: Boolean): List<RvNode> = if (steadiest) nodes.steadiest(TOP) else nodes.fastest(TOP)

    /** The nodes by part of the world, fastest first in each ([byRegion] by latency, which reads no title). */
    fun byRegion(): List<Pair<RvRegion, List<RvNode>>> = byRegion(RvNodeSort.Latency) { it.name }

    /**
     * The nodes by part of the world, the part with the fastest node first; in each, the 排序 chip's [order] (fastest
     * first by default), 依名稱 by the rows' [title]s.
     */
    fun byRegion(order: RvNodeSort, title: (RvNode) -> String): List<Pair<RvRegion, List<RvNode>>> = nodes
        .sortedWith(rvByLatency)
        .groupBy { it.place.region }
        .toList()
        .sortedBy { it.first == RvRegion.Other }
        .map { (region, members) -> region to if (order == RvNodeSort.Latency) members else rvSectionOrder(members, order, title) }
}

private const val TOP = 8
