package dev.doona.android.feature.model

import androidx.compose.runtime.Immutable
import dev.doona.android.engine.ScoreChallenger
import dev.doona.android.engine.ScoreNetwork

/**
 * What a Score group chose on one network, as the pages show it ([rvScorePick] maps honk's `scoreState`): the chosen node
 * ([selectedId]; [selectedName] alone for a subgroup or a tag honk cannot tie to one node; both null with no choice),
 * whether honk has settled on it ([settled], 已選定, or 評估中), what it waits for ([wait]), how many of the [candidates]
 * it has compared ([compared]), and the challengers in honk's order. Score compares; it gives no node a number.
 */
@Immutable
internal data class RvScorePick(
    val selectedId: String?,
    val selectedName: String?,
    val settled: Boolean,
    val wait: RvScoreWait?,
    val compared: Int,
    val candidates: Int,
    val challengers: List<RvScoreRival>,
) {
    /** Nothing chosen and nothing under way: no eligible candidate, so 評估中 would say what is not happening. */
    val idle: Boolean get() = selectedName == null && candidates == 0
}

/** What a Score group waits for before it compares again. */
internal enum class RvScoreWait { Traffic, Budget, Testing, Backoff }

/** A node that challenges the chosen one, by [nodeId] when honk ties it to one node, and how it compares ([relation]). */
@Immutable
internal data class RvScoreRival(val name: String, val nodeId: String?, val relation: RvScoreRelation?)

/** How a challenger compares to the chosen node: 較快, 相當 or 較慢. */
internal enum class RvScoreRelation { Faster, Similar, Slower }

/**
 * [network] as the pages show it. honk's `evaluated` are the members in its bounded comparison set and `pending` those
 * of them still with an open question, so their difference is the comparisons finished (已比較 n/m). A wait or a relation
 * honk names in a way this build does not know is left out.
 */
internal fun rvScorePick(network: ScoreNetwork): RvScorePick = RvScorePick(
    selectedId = network.selectedId,
    selectedName = network.selected,
    settled = network.state == OBSERVED_USABLE,
    wait = when (network.waitReason) {
        "comparableTraffic" -> RvScoreWait.Traffic
        "budget" -> RvScoreWait.Budget
        "inFlight" -> RvScoreWait.Testing
        "backoff" -> RvScoreWait.Backoff
        else -> null
    },
    compared = network.coverage.evaluated - network.coverage.pending,
    candidates = network.coverage.candidates,
    challengers = network.challengers.map(::rvScoreRival),
)

private fun rvScoreRival(challenger: ScoreChallenger) = RvScoreRival(
    name = challenger.name,
    nodeId = challenger.nodeId,
    relation = when (challenger.relation) {
        "challengerFaster" -> RvScoreRelation.Faster
        "equivalent" -> RvScoreRelation.Similar
        "selectedFaster" -> RvScoreRelation.Slower
        else -> null
    },
)

private const val OBSERVED_USABLE = "observedUsable"
