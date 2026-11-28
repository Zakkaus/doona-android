package dev.doona.android.feature.nodes.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RV_NO_VALUE
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
import dev.doona.android.feature.model.RvNode
import dev.doona.android.feature.model.RvScorePick
import dev.doona.android.feature.model.RvScoreRelation
import dev.doona.android.feature.model.RvScoreRival
import dev.doona.android.feature.model.RvScoreWait

/**
 * What a Score group chose on one network ([label], TCP or UDP), as honk reports it ([RvScorePick]): the card's header
 * carries how many of the candidates it has compared (its tag); the first row is the chosen node, badged as the group
 * page badges its choice, its line the state in words (已選定 or 評估中) and what it waits for, its value the node's last
 * latency test as on every node row; then each challenger, in 節點's row, with how it compares to the chosen node (較快,
 * 相當, 較慢). Score only compares; it gives no node a number of its own, so the card shows none. With no eligible
 * candidate there is no choice: the row says 沒有可用的節點.
 */
@Composable
internal fun RvScoreCard(label: String, pick: RvScorePick, members: List<RvNode>) {
    RvCard {
        RvCardHeader(label, tag = stringResource(R.string.rv_score_coverage, pick.compared, pick.candidates))
        RvScoreChoiceRow(pick, members, pick.stateText(), latency = true)
        for (rival in pick.challengers) Challenger(rival, members)
    }
}

/**
 * The node a Score group chose on one network, badged, over [line]; with [latency], its last test at the end. A subgroup
 * or a tag honk cannot tie to one node shows its name only; with no eligible candidate the row says 沒有可用的節點, over
 * [line] only when it has one.
 */
@Composable
internal fun RvScoreChoiceRow(pick: RvScorePick, members: List<RvNode>, line: String?, latency: Boolean = false) {
    val chosen = members.firstOrNull { it.id == pick.selectedId }
    when {
        chosen != null -> RvRow(
            { RvFlag(chosen.place.flag, RvSize.rowDisc, current = true) },
            chosen.name,
            line,
            titleEnd = if (latency) ({ RvLatencyText(chosen.latencyMs, chosen.timedOut) }) else null,
        )

        // A subgroup, or a tag honk cannot tie to one node: its name only.
        pick.selectedName != null -> RvRow({ RvIconDisc(R.drawable.rv_sym_account_tree) }, pick.selectedName, line)

        else -> RvRow({ RvIconDisc(R.drawable.rv_sym_block) }, stringResource(R.string.rv_no_node), line)
    }
}

/** The state in words, then what it waits for when it waits. Null when nothing is chosen or under way ([RvScorePick.idle]). */
@Composable
internal fun RvScorePick.stateText(): String? {
    if (settled == null) return RV_NO_VALUE // honk-gap:score-evidence
    if (idle) return null
    val word = stringResource(if (settled) R.string.rv_score_selected else R.string.rv_score_evaluating)
    val waiting = when (wait) {
        RvScoreWait.Traffic -> R.string.rv_score_wait_traffic
        RvScoreWait.Budget -> R.string.rv_score_wait_budget
        RvScoreWait.Testing -> R.string.rv_score_wait_testing
        RvScoreWait.Backoff -> R.string.rv_score_wait_backoff
        null -> null
    }
    return listOfNotNull(word, waiting?.let { stringResource(it) }).joinToString(SEPARATOR)
}

/** A challenger as 節點 lists a node, and at its end how it compares to the chosen node. */
@Composable
private fun Challenger(rival: RvScoreRival, members: List<RvNode>) {
    val node = members.firstOrNull { it.id == rival.nodeId }
    val relation = when (rival.relation) {
        RvScoreRelation.Faster -> R.string.rv_score_faster
        RvScoreRelation.Similar -> R.string.rv_score_similar
        RvScoreRelation.Slower -> R.string.rv_score_slower
        null -> null
    }
    RvRow(
        { if (node != null) RvFlag(node.place.flag, RvSize.rowDisc) else RvIconDisc(R.drawable.rv_sym_account_tree) },
        rival.name,
        node?.let { rvNodeLine(it.protocol, stringResource(it.place.label)) },
        titleEnd = relation?.let { { RvText(stringResource(it), Rv.type.rowValue, align = TextAlign.End) } },
    )
}
