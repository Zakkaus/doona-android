package dev.doona.android.feature.nodes

import androidx.compose.runtime.Immutable
import dev.doona.android.designsystem.rv.RvPlace
import dev.doona.android.designsystem.rv.RvRegion
import dev.doona.android.feature.model.RvNode
import dev.doona.android.feature.model.RvNodeSort
import dev.doona.android.feature.model.RvSubscription
import dev.doona.android.feature.model.fastest
import dev.doona.android.feature.model.rvSectionOrder
import dev.doona.android.feature.model.steadiest

/**
 * The 節點 page: every node, the one in use, when the rounds of tests ran (oldest first), and the subscriptions its 訂閱
 * chip offers.
 */
@Immutable
internal class RvNodesPageState(
    val nodes: List<RvNode>,
    val currentId: String,
    val testedAtEpochMs: List<Long>,
    val subscriptions: List<RvSubscription> = emptyList(),
) {
    /** The page for the 訂閱 chip's [subscription]: its nodes only, or every node for null. */
    fun scoped(subscription: String?): RvNodesPageState =
        if (subscription == null) this else RvNodesPageState(nodes.filter { it.subscription == subscription }, currentId, testedAtEpochMs, subscriptions)

    /** The countries the map marks and the count counts: those with a spot on the map. */
    val places: Set<RvPlace> get() = nodes.mapTo(mutableSetOf()) { it.place }.filterTo(mutableSetOf()) { it.mapped }

    /** The eight fastest nodes by their last test, or the eight steadiest over all tests. */
    fun top(steadiest: Boolean): List<RvNode> = if (steadiest) nodes.steadiest(TOP) else nodes.fastest(TOP)

    /**
     * The nodes by part of the world, the part with the fastest node first; in each, the 排序 chip's [order] (fastest
     * first by default).
     */
    fun byRegion(order: RvNodeSort = RvNodeSort.Latency): List<Pair<RvRegion, List<RvNode>>> = nodes
        .sortedWith(compareBy(nullsLast()) { it.latencyMs })
        .groupBy { it.place.region }
        .toList()
        .sortedBy { it.first == RvRegion.Other }
        .map { (region, members) -> region to if (order == RvNodeSort.Latency) members else rvSectionOrder(members, order) }
}

private const val TOP = 8
