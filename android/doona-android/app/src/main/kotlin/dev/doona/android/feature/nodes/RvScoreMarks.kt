package dev.doona.android.feature.nodes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.res.stringResource
import dev.doona.android.R
import dev.doona.android.engine.ScoreGroup

/**
 * What 節點 and a node's page show of Score: Score compares nodes and gives none a number, so only the nodes a Score
 * group in [groups] chose carry a mark. On 節點 a node chosen on TCP and UDP reads 「Score」; the network is named only
 * where it tells the node apart, on a node chosen on one of them: 「Score TCP」 or 「Score UDP」. A node's own page always
 * names the networks: 「Score TCP/UDP」 there.
 */
@Immutable
internal class RvScoreMarks(val groups: List<ScoreGroup>) {
    /** The mark of [node] on 節點, or null where no group chose it. */
    @Composable
    fun mark(node: String): String? {
        val both = groups.any { it.tcp.selectedId == node } && groups.any { it.udp.selectedId == node }
        return if (both) stringResource(R.string.rv_score_mark) else detailMark(node)
    }

    /** The mark of [node] on its own page, with every network that chose it, or null where no group chose it. */
    @Composable
    fun detailMark(node: String): String? = listOfNotNull(
        stringResource(R.string.ui_tcp).takeIf { groups.any { it.tcp.selectedId == node } },
        stringResource(R.string.ui_udp).takeIf { groups.any { it.udp.selectedId == node } },
    ).takeIf { it.isNotEmpty() }?.let { stringResource(R.string.rv_score_pick, it.joinToString("/")) }
}
