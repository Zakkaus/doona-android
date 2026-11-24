package dev.doona.android.prototype.rv.nodes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.res.stringResource
import dev.doona.android.R
import dev.doona.android.engine.ScoreGroup

/**
 * What 節點 and a node's page show of Score: Score compares nodes and gives none a number, so only the nodes a Score
 * group in [groups] chose carry a mark, 「Score TCP」, 「Score UDP」 or 「Score TCP/UDP」 after the networks it was chosen on,
 * as the group's page names them.
 */
@Immutable
internal class RvScoreMarks(val groups: List<ScoreGroup>) {
    /** The mark of [node], or null where no group chose it. */
    @Composable
    fun mark(node: String): String? {
        val on = listOfNotNull(
            stringResource(R.string.ui_tcp).takeIf { groups.any { it.tcp.selectedId == node } },
            stringResource(R.string.ui_udp).takeIf { groups.any { it.udp.selectedId == node } },
        )
        return if (on.isEmpty()) null else stringResource(R.string.rv_score_pick, on.joinToString("/"))
    }
}
