package dev.doona.android.prototype.rv.data

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RvPlace

/**
 * How a node list orders its rows (the picker under 全域, the main node's picker). 延遲, 穩定 and 名稱 order the nodes inside
 * each subscription's card; 地區 puts them in one card per country across the subscriptions. Whatever the key, a node
 * whose last test timed out or that was never tested comes last.
 */
internal enum class RvNodeSort(@StringRes val label: Int) {
    /**
     * The last test, as 活動's 最快節點 card orders ([fastest]) and as each row shows it. The last test rather than the
     * median: honk's url-test groups rank by a moving average that halves the weight of every older test
     * (honk-outbound/src/alive/collection.rs:87-94, group/policy.rs:410-420), so the newest test is the closer guide to
     * the node honk itself would pick; the median is node detail's figure.
     */
    Latency(R.string.rv_nodes_sort_latency),

    /** How many of the kept tests answered, more first, then how little the answered ones spread ([rvSteadiness]). */
    Stability(R.string.rv_nodes_sort_stability),

    /** The node's name, as the subscription gives it. */
    Name(R.string.rv_nodes_sort_name),

    /** One card per country, the country with the fastest node first, its nodes fastest first. */
    Region(R.string.rv_nodes_sort_region),
}

/** A node whose last test answered before one whose last test timed out or that was never tested. */
private val lastAnswered: Comparator<RvNode> = compareBy { it.latencyMs == null }

/** Fastest first by the last test. */
internal val rvByLatency: Comparator<RvNode> = lastAnswered.thenBy { it.latencyMs ?: Int.MAX_VALUE }

/**
 * Steadiest first: the success ratio ([RvNode.answered] of the [RV_TESTS] kept), then the jitter of the answered tests
 * ([RvNode.spreadMs]), then the last test. honk keeps no such per-node figure for the app: its native API gives a node's
 * state, last latency and two averages (honk-core/src/native_api/catalog.rs:110-124), and the per-node history it keeps
 * (honk-outbound/src/alive/mod.rs:331, 100 records, alive/probe.rs:782) is not served. So the app derives it from the
 * tests it keeps.
 */
internal val rvSteadiness: Comparator<RvNode> = lastAnswered
    .thenByDescending { it.answered }
    .thenBy { it.spreadMs ?: Double.MAX_VALUE }
    .thenBy { it.latencyMs ?: Int.MAX_VALUE }

/** By name, ignoring case; the numbers in the names are zero-padded, so they order as numbers. */
internal val rvByName: Comparator<RvNode> = lastAnswered.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }

/** One card of a node list: a subscription's nodes, or a country's, in the list's order. */
@Immutable
internal sealed class RvNodeGroup(val key: String, val nodes: List<RvNode>) {
    class Subscription(val subscription: RvSubscription, nodes: List<RvNode>) : RvNodeGroup(subscription.id, nodes)

    class Place(val place: RvPlace, nodes: List<RvNode>) : RvNodeGroup(place.name, nodes)
}

/** [nodes] as a list shows them under [sort]: by subscription in [subscriptions]' order, or by country for 地區. */
internal fun rvNodeGroups(nodes: List<RvNode>, subscriptions: List<RvSubscription>, sort: RvNodeSort): List<RvNodeGroup> = when (sort) {
    // groupBy keeps the order in which each country first appears, so the country with the fastest node leads.
    // 其他 closes the list, whatever its nodes' latency; the sort keeps every other card where it was.
    RvNodeSort.Region -> nodes.sortedWith(rvByLatency).groupBy { it.place }.map { (place, members) -> RvNodeGroup.Place(place, members) }
        .sortedBy { it.place == RvPlace.Other }

    else -> {
        val order = when (sort) {
            RvNodeSort.Stability -> rvSteadiness
            RvNodeSort.Name -> rvByName
            else -> rvByLatency
        }
        subscriptions.map { sub -> RvNodeGroup.Subscription(sub, nodes.filter { it.subscription == sub.name }.sortedWith(order)) }
    }
}
