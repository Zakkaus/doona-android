package dev.doona.android.prototype.rv.nodes

import androidx.compose.runtime.Immutable
import dev.doona.android.designsystem.rv.RvPlace
import dev.doona.android.designsystem.rv.RvRegion
import dev.doona.android.feature.model.RvNode
import dev.doona.android.feature.model.fastest
import dev.doona.android.feature.model.steadiest
import dev.doona.android.prototype.rv.data.RvData

/** The 節點 page: every node, the one in use, and when the rounds of tests ran, oldest first. */
@Immutable
internal class RvNodesPageState(val nodes: List<RvNode>, val currentId: String, val testedAtEpochMs: List<Long>) {
    /** The countries the map marks and the count counts: those with a spot on the map. */
    val places: Set<RvPlace> get() = nodes.mapTo(mutableSetOf()) { it.place }.filterTo(mutableSetOf()) { it.mapped }

    /** The eight fastest nodes by their last test, or the eight steadiest over all tests. */
    fun top(steadiest: Boolean): List<RvNode> = if (steadiest) nodes.steadiest(TOP) else nodes.fastest(TOP)

    /** The nodes by part of the world, fastest first in each; the part with the fastest node comes first. */
    fun byRegion(): List<Pair<RvRegion, List<RvNode>>> = nodes
        .sortedWith(compareBy(nullsLast()) { it.latencyMs })
        .groupBy { it.place.region }
        .toList()
        .sortedBy { it.first == RvRegion.Other }
}

private const val TOP = 8

/** The page as [RvData] has it. */
internal fun sampleNodesPage() = RvNodesPageState(RvData.nodes, RvData.CURRENT_ID, RvData.testTimes)

/** The node the detail page's golden shows: sg-03, whose last tests slow down steadily. */
internal const val RV_NODES_DETAIL = "sg-03"
