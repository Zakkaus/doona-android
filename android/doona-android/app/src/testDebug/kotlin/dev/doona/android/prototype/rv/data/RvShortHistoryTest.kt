package dev.doona.android.prototype.rv.data

import dev.doona.android.feature.model.RV_TESTS
import dev.doona.android.feature.model.steadiest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A node the engine has probed once, or not at all, as the app meets it after a subscription is added. */
class RvShortHistoryTest {
    private val node = RvData.nodes.first { it.id == "sg-03" }

    @Test
    fun aNodeWithNoProbeHasNoFigureNoChangeAndNoMedian() {
        val none = node.copy(history = emptyList())
        assertNull(none.latencyMs)
        assertNull(none.changeMs)
        assertNull(none.medianMs)
        assertNull(none.spreadMs)
        assertEquals(0, none.answered)
        assertFalse("no probe is not a timeout", none.timedOut)
        assertTrue(none.withTest(null).timedOut)
        assertFalse(none.withTest(null).untested().timedOut)
        assertEquals("steadiest() must not lead with a node nobody has probed", Double.MAX_VALUE, none.jitterMs, 0.0)
    }

    @Test
    fun aNodeWithOneProbeHasItsFigureAndNothingToCompareItWith() {
        val one = node.copy(history = listOf(92))
        assertEquals(92, one.latencyMs)
        assertNull("no test before it", one.changeMs)
        assertNull("the middle of one test is the test", one.medianMs)
        assertNull(one.spreadMs)
        assertEquals(1, one.answered)
        assertFalse(one.timedOut)
        assertEquals("one test cannot show a node is steady", Double.MAX_VALUE, one.jitterMs, 0.0)
    }

    @Test
    fun theFullHistoryReadsAsBefore() {
        assertEquals(186, node.latencyMs)
        assertEquals(14, node.changeMs)
        assertEquals(RV_TESTS, node.history.size)
    }
}
