package dev.doona.android.feature.model

import androidx.compose.runtime.Immutable
import dev.doona.android.designsystem.rv.RvPlace
import dev.doona.android.designsystem.rv.RvSpot
import kotlin.math.sqrt

/**
 * One node: what its share link says (protocol, server, port, transport, TLS), the subscription it came from, and the
 * results of its last latency tests, at most [RV_TESTS], oldest first (a node new to the app has none or one); null is a
 * test that timed out. [name] is the one name every
 * page shows (`Hong Kong 02 IPLC`); [short] is the same node's country code and number for the narrow tiles (`HK 02`).
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
    val history: List<Int?>,
) {
    /** Where the maps mark the node: its city when its name gives one, else its country's centroid; none for 其他. */
    val spot: RvSpot? get() = if (place.mapped) city?.spot ?: place.spot else null

    /** The last test's result; null for a test that timed out, and for a node with no test yet. */
    val latencyMs: Int? get() = history.lastOrNull()

    /** The change from the test before, when both answered: positive is slower. */
    val changeMs: Int? get() = history.getOrNull(history.size - 2)?.let { before -> latencyMs?.let { it - before } }

    /** The middle of the answered tests ([rvMedian]); null with fewer than two, where the middle is the test itself and says nothing more. */
    val medianMs: Int? get() = if (answered < 2) null else rvMedian(history.filterNotNull())

    /** How many of the kept tests answered. */
    val answered: Int get() = history.count { it != null }

    /**
     * The last test timed out (every failed test in the sample is a timeout). A node none of whose kept tests answered
     * reads as never tested, as [untested] makes it: the sample keeps no other trace of a test that ran.
     */
    val timedOut: Boolean get() = latencyMs == null && answered > 0

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
    fun untested(): RvNode = copy(history = List(history.size) { null })
}

/** The [count] fastest nodes by their last test, ties in list order; every page's 最快 list is this one. */
internal fun List<RvNode>.fastest(count: Int): List<RvNode> = filter { it.latencyMs != null }.sortedBy { it.latencyMs }.take(count)

/** The [count] steadiest nodes over all tests. */
internal fun List<RvNode>.steadiest(count: Int): List<RvNode> = sortedBy { it.jitterMs }.take(count)

/** The middle of [values], the mean of the two middle ones when there are an even number; null when there are none. */
internal fun rvMedian(values: List<Int>): Int? = values.sorted().takeIf { it.isNotEmpty() }?.let { (it[(it.size - 1) / 2] + it[it.size / 2]) / 2 }

/** How many latency tests each node keeps. */
internal const val RV_TESTS = 8
