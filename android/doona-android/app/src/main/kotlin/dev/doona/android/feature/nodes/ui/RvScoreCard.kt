package dev.doona.android.feature.nodes.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import dev.doona.android.R
import dev.doona.android.designsystem.rv.Rv
import dev.doona.android.designsystem.rv.RvCard
import dev.doona.android.designsystem.rv.RvCardHeader
import dev.doona.android.designsystem.rv.RvFlag
import dev.doona.android.designsystem.rv.RvIconDisc
import dev.doona.android.designsystem.rv.RvLatencyText
import dev.doona.android.designsystem.rv.RvRow
import dev.doona.android.designsystem.rv.RvSize
import dev.doona.android.designsystem.rv.RvText
import dev.doona.android.designsystem.rv.SEPARATOR
import dev.doona.android.engine.ScoreChallenger
import dev.doona.android.engine.ScoreNetwork
import dev.doona.android.feature.model.RvNode

/**
 * What a Score group chose on one network ([label], TCP or UDP), as honk reports it (`scoreState`): the card's header
 * carries how many of the candidates it has evaluated (its tag); the first row is the chosen node, badged as the group
 * page badges its choice, its line the state in words (已選定 or 評估中) and what it waits for, its value the node's last
 * latency test as on every node row; then each challenger, in 節點's row, with how it compares to the chosen node (較快,
 * 相當, 較慢). Score only compares; it gives no node a number of its own, so the card shows none. With no eligible
 * candidate there is no choice: the row says 沒有可用的節點.
 */
@Composable
internal fun RvScoreCard(label: String, network: ScoreNetwork, members: List<RvNode>) {
    val coverage = network.coverage
    RvCard {
        // honk's `evaluated` are the members in its bounded comparison set and `pending` those of them still with an open
        // question, so the difference is the comparisons finished: 已比較 n/m.
        RvCardHeader(label, tag = stringResource(R.string.rv_score_coverage, coverage.evaluated - coverage.pending, coverage.candidates))
        RvScoreChoiceRow(network, members, network.stateText(), latency = true)
        for (challenger in network.challengers) Challenger(challenger, members)
    }
}

/**
 * The node a Score group chose on one network, badged, over [line]; with [latency], its last test at the end. A subgroup
 * or a tag honk cannot tie to one node shows its name only; with no eligible candidate the row says 沒有可用的節點, over
 * [line] only when it has one.
 */
@Composable
internal fun RvScoreChoiceRow(network: ScoreNetwork, members: List<RvNode>, line: String?, latency: Boolean = false) {
    val chosen = members.firstOrNull { it.id == network.selectedId }
    when {
        chosen != null -> RvRow(
            { RvFlag(chosen.place.flag, RvSize.rowDisc, current = true) },
            chosen.name,
            line,
            titleEnd = if (latency) ({ RvLatencyText(chosen.latencyMs, chosen.timedOut) }) else null,
        )

        // A subgroup, or a tag honk cannot tie to one node: its name only.
        network.selected != null -> RvRow({ RvIconDisc(R.drawable.rv_sym_account_tree) }, network.selected, line)

        else -> RvRow({ RvIconDisc(R.drawable.rv_sym_block) }, stringResource(R.string.rv_no_node), line)
    }
}

/**
 * The state in words, then what it waits for when it waits. Null with no eligible candidate: nothing is chosen and
 * nothing is under way, so 評估中 would say what is not happening.
 */
@Composable
internal fun ScoreNetwork.stateText(): String? {
    if (selected == null && coverage.candidates == 0) return null
    val word = stringResource(if (state == OBSERVED_USABLE) R.string.rv_score_selected else R.string.rv_score_evaluating)
    val wait = when (waitReason) {
        "comparableTraffic" -> R.string.rv_score_wait_traffic
        "budget" -> R.string.rv_score_wait_budget
        "inFlight" -> R.string.rv_score_wait_testing
        "backoff" -> R.string.rv_score_wait_backoff
        else -> null
    }
    return listOfNotNull(word, wait?.let { stringResource(it) }).joinToString(SEPARATOR)
}

/** A challenger as 節點 lists a node, and at its end how it compares to the chosen node. */
@Composable
private fun Challenger(challenger: ScoreChallenger, members: List<RvNode>) {
    val node = members.firstOrNull { it.id == challenger.nodeId }
    val relation = when (challenger.relation) {
        "challengerFaster" -> R.string.rv_score_faster
        "equivalent" -> R.string.rv_score_similar
        "selectedFaster" -> R.string.rv_score_slower
        else -> null
    }
    RvRow(
        { if (node != null) RvFlag(node.place.flag, RvSize.rowDisc) else RvIconDisc(R.drawable.rv_sym_account_tree) },
        challenger.name,
        node?.let { it.protocol + SEPARATOR + stringResource(it.place.label) },
        titleEnd = relation?.let { { RvText(stringResource(it), Rv.type.rowValue, align = TextAlign.End) } },
    )
}

private const val OBSERVED_USABLE = "observedUsable"
