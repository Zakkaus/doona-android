package dev.doona.android.prototype.rv.data

import androidx.compose.runtime.Immutable

/** A node whose last test answered before one whose last test timed out or that was never tested. */
private val lastAnswered: Comparator<RvNode> = compareBy { it.latencyMs == null }

/**
 * Fastest first by the last test, as 活動's 最快節點 card orders ([fastest]) and as each row shows it; a node whose last
 * test timed out or that was never tested comes last.
 */
internal val rvByLatency: Comparator<RvNode> = lastAnswered.thenBy { it.latencyMs ?: Int.MAX_VALUE }

/** One card of a node list: a subscription's nodes, in the list's order. */
@Immutable
internal class RvNodeGroup(val subscription: RvSubscription, val nodes: List<RvNode>) {
    val key: String get() = subscription.id
}

/** [nodes] as a node list shows them: one group per subscription in [subscriptions]' order, fastest first in each. */
internal fun rvNodeGroups(nodes: List<RvNode>, subscriptions: List<RvSubscription>): List<RvNodeGroup> =
    subscriptions.map { sub -> RvNodeGroup(sub, nodes.filter { it.subscription == sub.name }.sortedWith(rvByLatency)) }
