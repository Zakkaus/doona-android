package dev.doona.android.feature.model

import androidx.compose.runtime.Immutable
import dev.doona.android.designsystem.rv.RvPlace
import dev.doona.android.designsystem.rv.RvSpot
import kotlin.math.sqrt

/**
 * One node: what its share link says (protocol, server, port, transport, TLS), the subscription it came from, and the
 * results of its last latency tests, at most [RV_TESTS], oldest first (a node new to the app has none or one); null is a
 * test that timed out. The history is the newest tests in the order they ran: a new test goes at the end and, once the
 * history is full, the oldest one goes from the front ([withTest]); no test ever leaves from the middle. The detail
 * page pairs the history with the list of test times from its end, so a history that skipped a test would show every
 * earlier figure under the wrong time. [name] is the one name every
 * page shows (`Hong Kong 02 IPLC`); [short] is the same node's country code and number for the narrow tiles (`HK 02`).
 * [subscription] is its subscription's name, as a node's page shows it; [subscriptionId] is the subscription itself, which
 * the lists group and filter by: two subscriptions may share a name (one host, two tokens). [shareable]: the core keeps
 * the node's original share link, which it does only for a share-link subscription's node (`Engine.nodeShareLink`).
 * [trendMs] is honk's trend, the last probe against the average of the last ten (`latency_ms` − `avg10_ms`); null where
 * honk has no such average, and after a test of the app's own ([withTest]).
 */
@Immutable
internal data class RvNode(
    val id: String,
    val name: String,
    val short: String,
    val place: RvPlace,
    val city: RvCity?,
    val protocol: String,
    val host: String,
    val port: Int,
    val transport: String,
    val tls: Boolean,
    val subscription: String,
    val subscriptionId: String,
    val history: List<Int?>,
    val shareable: Boolean,
    val trendMs: Int? = null,
) {
    /** A node the profile lists itself, of no subscription (組態內): its [subscriptionId] is [RV_INLINE]. */
    val inline: Boolean get() = subscriptionId == RV_INLINE

    /** Whether the core sent the node's details (its server); a node read before them shows none, and no share link. */
    val described: Boolean get() = host.isNotEmpty()

    /** Where the maps mark the node: its city when its name gives one, else its country's centroid; none for 其他. */
    val spot: RvSpot? get() = if (place.mapped) city?.spot ?: place.spot else null

    /** The last test's result; null for a test that timed out, and for a node with no test yet. */
    val latencyMs: Int? get() = history.lastOrNull()

    /**
     * The change shown under the latency, positive being slower: [trendMs] when set, else the change from the test
     * before, when both answered.
     */
    val changeMs: Int? get() = trendMs ?: history.getOrNull(history.size - 2)?.let { before -> latencyMs?.let { it - before } }

    /** The middle of the answered tests ([rvMedian]); null with fewer than two, where the middle is the test itself and says nothing more. */
    val medianMs: Int? get() = if (answered < 2) null else rvMedian(history.filterNotNull())

    /** How many of the kept tests answered. */
    val answered: Int get() = history.count { it != null }

    /** An observed failure, unlike an empty history before the first test. */
    val timedOut: Boolean get() = history.isNotEmpty() && latencyMs == null

    /**
     * How far the answered tests stray from their mean: their standard deviation in ms. Null with fewer than two answered
     * tests, which say nothing about spread.
     */
    val spreadMs: Double?
        get() {
            val values = history.filterNotNull().takeIf { it.size >= 2 } ?: return null
            val mean = values.average()
            return sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
        }

    /** [spreadMs] over all tests; a node that ever timed out, or has too few tests to tell, is the least steady. */
    val jitterMs: Double get() = if (answered < history.size) Double.MAX_VALUE else spreadMs ?: Double.MAX_VALUE

    /** The same node before any test ran. */
    fun untested(): RvNode = copy(history = emptyList(), trendMs = null)

    /** This node after one more test, [ms] being null when it timed out: the history keeps the newest [RV_TESTS] tests. */
    fun withTest(ms: Int?): RvNode = copy(history = (history + ms).takeLast(RV_TESTS), trendMs = null)
}

/** The [count] fastest nodes by their last test, ties in list order; every page's 最快 list is this one. */
internal fun List<RvNode>.fastest(count: Int): List<RvNode> = filter { it.latencyMs != null }.sortedBy { it.latencyMs }.take(count)

/** The [count] steadiest nodes over all tests. */
internal fun List<RvNode>.steadiest(count: Int): List<RvNode> = filter { it.answered >= 2 }.sortedBy { it.jitterMs }.take(count)

/** The middle of [values], the mean of the two middle ones when there are an even number; null when there are none. */
internal fun rvMedian(values: List<Int>): Int? = values.sorted().takeIf { it.isNotEmpty() }?.let { (it[(it.size - 1) / 2] + it[it.size / 2]) / 2 }

/** How many latency tests each node keeps. */
internal const val RV_TESTS = 8

/** The [RvNode.subscriptionId] of a node of no subscription (組態內), and the 訂閱 chip's pick of those nodes. */
internal const val RV_INLINE = ""
