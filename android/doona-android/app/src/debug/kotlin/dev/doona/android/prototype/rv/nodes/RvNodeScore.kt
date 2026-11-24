package dev.doona.android.prototype.rv.nodes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.res.stringResource
import dev.doona.android.R
import dev.doona.android.engine.ScoreGroup
import dev.doona.android.prototype.rv.data.RvNode
import dev.doona.android.prototype.rv.data.rvMedian
import kotlin.math.roundToInt

/**
 * What 節點 and a node's page show of Score, a pair for the owner (both samples, no pick):
 * - [Marks] is honest to honk: Score compares nodes and gives none a number, so only the nodes a Score group chose carry
 *   a mark, 「Score 選用」, with the network when the group chose a different node on TCP and on UDP;
 * - [Composite] is doona's own 綜合評分, 0 to 100 for every tested node ([composite]); honk does not compute it.
 */
@Immutable
internal sealed interface RvScoreLook {
    class Marks(val groups: List<ScoreGroup>) : RvScoreLook

    data object Composite : RvScoreLook
}

/** The mark of [node] under [RvScoreLook.Marks]: 「Score 選用」, then TCP or UDP when only one network chose it; null where none did. */
@Composable
internal fun RvScoreLook.Marks.mark(node: String): String? {
    val tcp = groups.any { it.tcp.selectedId == node }
    val udp = groups.any { it.udp.selectedId == node }
    return when {
        tcp && udp -> stringResource(R.string.rv_score_pick)
        tcp -> stringResource(R.string.rv_score_pick_on, stringResource(R.string.ui_tcp))
        udp -> stringResource(R.string.rv_score_pick_on, stringResource(R.string.ui_udp))
        else -> null
    }
}

/** [composite] as a row's second line or a stat: 「86 分」, or 未測試 for a node with no answered test. */
@Composable
internal fun compositeText(node: RvNode): String =
    node.composite()?.let { stringResource(R.string.rv_score_points, it) } ?: stringResource(R.string.latency_untested)

/**
 * doona's 綜合評分 of a node over its kept tests (the last [dev.doona.android.prototype.rv.data.RV_TESTS]):
 * 0.4 × 延遲 + 0.4 × 穩定 + 0.2 × 最近, rounded, where
 * - 延遲 is 100 at a median of [FAST_MS] or less, falling evenly to 0 at [SLOW_MS];
 * - 穩定 is the share of tests that answered × (1 − their spread / [SPREAD_MS], at least 0), as 穩定 sorts by;
 * - 最近 is 100 when the last test answered, else 0.
 * Null for a node none of whose tests answered: it has no score, not a score of 0.
 */
internal fun RvNode.composite(): Int? {
    val answeredMs = history.filterNotNull()
    val median = rvMedian(answeredMs) ?: return null
    val speed = ((SLOW_MS - median) / (SLOW_MS - FAST_MS) * FULL).coerceIn(0.0, FULL)
    val share = answeredMs.size.toDouble() / history.size
    val steady = share * (1 - (spreadMs ?: 0.0) / SPREAD_MS).coerceAtLeast(0.0) * FULL
    val recent = if (latencyMs != null) FULL else 0.0
    return (WEIGHT_SPEED * speed + WEIGHT_STEADY * steady + WEIGHT_RECENT * recent).roundToInt()
}

private const val FULL = 100.0
private const val FAST_MS = 50.0
private const val SLOW_MS = 500.0
private const val SPREAD_MS = 100.0
private const val WEIGHT_SPEED = 0.4
private const val WEIGHT_STEADY = 0.4
private const val WEIGHT_RECENT = 0.2
