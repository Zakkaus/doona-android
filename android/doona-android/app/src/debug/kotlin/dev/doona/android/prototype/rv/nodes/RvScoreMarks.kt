package dev.doona.android.prototype.rv.nodes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.res.stringResource
import dev.doona.android.R
import dev.doona.android.engine.ScoreGroup

/**
 * What 節點 and a node's page show of Score: Score compares nodes and gives none a number, so only the nodes a Score
 * group in [groups] chose carry a mark. A node chosen on TCP and UDP reads 「Score」; the network is named only where it
 * tells the node apart, on a node chosen on one of them: 「Score TCP」 or 「Score UDP」.
 */
@Immutable
internal class RvScoreMarks(val groups: List<ScoreGroup>) {
    /** The mark of [node], or null where no group chose it. */
    @Composable
    fun mark(node: String): String? {
        val tcp = groups.any { it.tcp.selectedId == node }
        val udp = groups.any { it.udp.selectedId == node }
        return when {
            tcp && udp -> stringResource(R.string.rv_score_mark)
            tcp -> stringResource(R.string.rv_score_pick, stringResource(R.string.ui_tcp))
            udp -> stringResource(R.string.rv_score_pick, stringResource(R.string.ui_udp))
            else -> null
        }
    }
}
