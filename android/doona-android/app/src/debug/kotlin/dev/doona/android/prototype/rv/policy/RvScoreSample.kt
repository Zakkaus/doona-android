package dev.doona.android.prototype.rv.policy

import dev.doona.android.engine.ScoreChallenger
import dev.doona.android.engine.ScoreCounters
import dev.doona.android.engine.ScoreCoverage
import dev.doona.android.engine.ScoreGroup
import dev.doona.android.engine.ScoreNetwork
import dev.doona.android.prototype.rv.data.RvNode

/**
 * A Score group for the group page's samples: the 香港 region as doona's templates write it (`policy: score`), with what
 * honk's `scoreState` would report for it in three moments, built on the region's nodes fastest first ([nodes]):
 * - [chosen]: both networks verified; TCP keeps the fastest node against three challengers (one of each relation), UDP
 *   chose the second against one;
 * - [evaluating]: both networks provisional, TCP with a trial in flight, UDP with its tests paused until ordinary traffic
 *   has earned their budget;
 * - [empty]: every node failed its last test, so no candidate is eligible and nothing is chosen.
 */
internal object RvScoreSample {
    private val region: RvPolicyGroup = RvPolicyData.group("hk")
    private val nodes: List<RvNode> = region.byLatency

    val group: RvPolicyGroup = RvPolicyGroup(GROUP, region.name, RvGroupKind.Score, region.members, nodes[0].id)

    /** The same region after every node's last test timed out. */
    val emptyGroup: RvPolicyGroup = RvPolicyGroup(GROUP, region.name, RvGroupKind.Score, region.members.map { it.failed() }, nodes[0].id)

    val chosen: ScoreGroup = ScoreGroup(
        GROUP,
        region.members.map { it.id },
        tcp = network(
            nodes[0],
            OBSERVED,
            NONE,
            ScoreCoverage(nodes.size, EVALUATED, 0),
            listOf(challenger(nodes[1], "equivalent"), challenger(nodes[2], "selectedFaster"), challenger(nodes[3], "challengerFaster")),
        ),
        udp = network(nodes[1], OBSERVED, NONE, ScoreCoverage(nodes.size, 2, 0), listOf(challenger(nodes[0], "equivalent"))),
    )

    val evaluating: ScoreGroup = ScoreGroup(
        GROUP,
        region.members.map { it.id },
        tcp = network(nodes[0], PROVISIONAL, "inFlight", ScoreCoverage(nodes.size, EVALUATED, EVALUATED - 1), listOf(challenger(nodes[1], "equivalent"))),
        udp = network(nodes[0], PROVISIONAL, "budget", ScoreCoverage(nodes.size, EVALUATED, EVALUATED), emptyList()),
    )

    val empty: ScoreGroup = ScoreGroup(GROUP, region.members.map { it.id }, tcp = nothing(), udp = nothing())

    private fun network(node: RvNode, state: String, wait: String, coverage: ScoreCoverage, challengers: List<ScoreChallenger>) =
        ScoreNetwork(node.name, node.id, state, wait, coverage, challengers, ScoreCounters(0, 0, 0, 0))

    private fun nothing() = ScoreNetwork(null, null, PROVISIONAL, NONE, ScoreCoverage(0, 0, 0), emptyList(), ScoreCounters(0, 0, 0, 0))

    private fun challenger(node: RvNode, relation: String) = ScoreChallenger(node.name, node.id, "commonTargets", relation, REPORTERS, VALID_FOR_MS)

    private fun RvNode.failed() = copy(history = history.dropLast(1) + null)
}

private const val GROUP = "score-hk"
private const val OBSERVED = "observedUsable"
private const val PROVISIONAL = "provisional"
private const val NONE = "none"

/** honk bounds the set it compares; four of the region's nodes are in it. */
private const val EVALUATED = 4
private const val REPORTERS = 4
private const val VALID_FOR_MS = 30_000L
