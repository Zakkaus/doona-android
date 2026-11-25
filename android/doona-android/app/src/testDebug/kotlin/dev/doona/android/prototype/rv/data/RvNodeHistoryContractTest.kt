package dev.doona.android.prototype.rv.data

import dev.doona.android.feature.model.RV_TESTS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The history keeps the newest [RV_TESTS] tests, oldest first, and only the oldest ever leaves it. The detail page
 * pairs the history with the test times from its end, so an entry removed from the middle would show every figure before
 * it under the wrong time.
 */
class RvNodeHistoryContractTest {
    private val node = RvData.nodes.first { it.id == "sg-03" }

    @Test
    fun aNewTestGoesAtTheEndAndTheOldestLeavesAFullHistory() {
        assertEquals(RV_TESTS, node.history.size)
        var now = node
        val ran = mutableListOf<Int?>()
        repeat(RV_TESTS + 3) { round ->
            val before = now.history
            val ms = if (round == 4) null else 200 + round
            now = now.withTest(ms)
            ran += ms
            assertEquals("never more than $RV_TESTS tests", RV_TESTS, now.history.size)
            assertEquals("the newest test is the last", ms, now.history.last())
            assertEquals("only the oldest left: every other test stays, in order", before.drop(1), now.history.dropLast(1))
        }
        assertEquals("after more tests than it holds, the history is the newest of them", ran.takeLast(RV_TESTS), now.history)
    }

    @Test
    fun aShortHistoryGrowsWithoutDroppingAnything() {
        var now = node.copy(history = emptyList())
        for (round in 1..RV_TESTS) {
            now = now.withTest(round)
            assertEquals((1..round).toList(), now.history)
        }
        assertTrue(now.withTest(RV_TESTS + 1).history.first() == 2)
    }
}
