package dev.doona.android.feature.model

import dev.doona.android.engine.ScoreChallenger
import dev.doona.android.engine.ScoreCounters
import dev.doona.android.engine.ScoreCoverage
import dev.doona.android.engine.ScoreNetwork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** honk's `scoreState` for one network as the Score cards read it. */
class RvScorePickTest {
    private fun network(selected: String?, state: String, wait: String, coverage: ScoreCoverage, challengers: List<ScoreChallenger> = emptyList()) =
        ScoreNetwork(selected, selected?.let { "n-$it" }, state, wait, coverage, challengers, ScoreCounters(0, 0, 0, 0))

    private fun rival(name: String, id: String?, relation: String) = ScoreChallenger(name, id, "latency", relation, 1, 0L)

    @Test
    fun mapsTheChoiceTheStateTheWaitTheComparedCountAndTheChallengers() {
        val pick = rvScorePick(
            network(
                "hk",
                "observedUsable",
                "comparableTraffic",
                ScoreCoverage(candidates = 5, evaluated = 4, pending = 1),
                listOf(rival("JP 01", "n-jp", "challengerFaster"), rival("Asia", null, "equivalent"), rival("US 01", "n-us", "selectedFaster"), rival("X", null, "new")),
            ),
        )
        assertEquals("n-hk", pick.selectedId)
        assertEquals("hk", pick.selectedName)
        assertTrue(pick.settled)
        assertEquals(RvScoreWait.Traffic, pick.wait)
        assertEquals(3, pick.compared)
        assertEquals(5, pick.candidates)
        assertEquals(
            listOf(RvScoreRival("JP 01", "n-jp", RvScoreRelation.Faster), RvScoreRival("Asia", null, RvScoreRelation.Similar), RvScoreRival("US 01", "n-us", RvScoreRelation.Slower), RvScoreRival("X", null, null)),
            pick.challengers,
        )
        assertFalse(pick.idle)
    }

    @Test
    fun anUnknownWaitIsLeftOutAndNoCandidateIsIdle() {
        val pick = rvScorePick(network(null, "evaluating", "somethingNew", ScoreCoverage(0, 0, 0)))
        assertEquals(null, pick.wait)
        assertFalse(pick.settled)
        assertTrue(pick.idle)
        assertEquals(listOf(RvScoreWait.Budget, RvScoreWait.Testing, RvScoreWait.Backoff), listOf("budget", "inFlight", "backoff").map { rvScorePick(network("a", "", it, ScoreCoverage(1, 1, 0))).wait })
    }
}
