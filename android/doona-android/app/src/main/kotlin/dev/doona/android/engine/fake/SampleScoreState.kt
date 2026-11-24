package dev.doona.android.engine.fake

import dev.doona.android.engine.Group
import dev.doona.android.engine.Member
import dev.doona.android.engine.ScoreChallenger
import dev.doona.android.engine.ScoreCounters
import dev.doona.android.engine.ScoreCoverage
import dev.doona.android.engine.ScoreGroup
import dev.doona.android.engine.ScoreNetwork
import dev.doona.android.engine.ScoreState

internal fun sampleScoreState(groups: List<Group>): ScoreState {
    val scores = groups.filter { it.id == "g-Auto" || it.id == "g-HK" }.mapNotNull { group ->
        val nodes = group.members.filterNot { it.isGroup || it.failed }
        if (nodes.size < 2) return@mapNotNull null
        val first = nodes[0]
        val second = nodes[1]
        val tcp = if (group.id == "g-Auto") {
            ScoreNetwork(
                selected = first.name,
                selectedId = first.id,
                state = "observedUsable",
                waitReason = "none",
                coverage = ScoreCoverage(nodes.size, nodes.size, 0),
                challengers = listOf(ScoreChallenger(second.name, second.id, "commonTargets", "selectedFaster", 4, 30_000)),
                counters = ScoreCounters(19, 3, 1, 2),
            )
        } else {
            waitingNetwork(first, nodes.size)
        }
        ScoreGroup(group.id, group.members.filterNot { it.isGroup }.map { it.id }, tcp, waitingNetwork(second, nodes.size))
    }
    return ScoreState(scores.sumOf { it.tcp.counters.businessStarts }, scores)
}

private fun waitingNetwork(selected: Member, candidates: Int) = ScoreNetwork(
    selected = selected.name,
    selectedId = selected.id,
    state = "provisional",
    waitReason = "comparableTraffic",
    coverage = ScoreCoverage(candidates, candidates, candidates),
    challengers = emptyList(),
    counters = ScoreCounters(0, 0, 0, 0),
)
