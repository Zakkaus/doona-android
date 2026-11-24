package dev.doona.android.prototype.rv.nodes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.res.stringResource
import dev.doona.android.R
import dev.doona.android.engine.ScoreGroup

/**
 * What 節點 and a node's page show of Score: Score compares nodes and gives none a number, so only the nodes a Score
 * group in [groups] chose carry a mark, 「Score 選用」, with the network when the group chose a different node on TCP and
 * on UDP.
 */
@Immutable
internal class RvScoreMarks(val groups: List<ScoreGroup>) {
    /** The mark of [node]: 「Score 選用」, then TCP or UDP when only one network chose it; null where none did. */
    @Composable
    fun mark(node: String): String? {
        val tcp = groups.any { it.tcp.selectedId == node }
        val udp = groups.any { it.udp.selectedId == node }
        return when {
            tcp && udp -> stringResource(R.string.rv_score_pick)
            tcp -> stringResource(R.string.rv_score_pick_on, stringResource(R.string.ui_tcp))
            udp -> stringResource(R.string.rv_score_pick_on, stringResource(R.string.ui_udp))
            else -> null
        }
    }
}
