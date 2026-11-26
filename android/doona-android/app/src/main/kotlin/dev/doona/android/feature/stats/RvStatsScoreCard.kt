package dev.doona.android.feature.stats

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RvCard
import dev.doona.android.designsystem.rv.RvCardHeader
import dev.doona.android.designsystem.rv.SEPARATOR
import dev.doona.android.feature.model.RvNode
import dev.doona.android.feature.model.RvScorePick
import dev.doona.android.feature.nodes.ui.RvScoreChoiceRow
import dev.doona.android.feature.nodes.ui.stateText

/**
 * 綜合評分: per Score group and network, the node it chose with its state, as the group page's first row draws it, its
 * line beginning with the group's name when there are several. honk reports no Score counters (connections started,
 * trials), so the card keeps only this ranking (owner, 2026-11-26, q-score).
 */
@Composable
internal fun RvScoreStatsCard(score: RvStatsScore) {
    RvCard {
        RvCardHeader(stringResource(R.string.policy_kind_score))
        // The group's name begins the line only when there is more than one group to tell apart.
        val named = score.groups.size > 1
        for (group in score.groups) {
            val name = group.name.takeIf { named }
            Choice(name, TCP, group.tcp, group.members)
            Choice(name, UDP, group.udp, group.members)
        }
    }
}

/** `TCP · 已選定` (`香港 · TCP · 已選定` with the [group]'s name) under the node the group chose on that network. */
@Composable
private fun Choice(group: String?, label: String, pick: RvScorePick, members: List<RvNode>) {
    RvScoreChoiceRow(pick, members, listOfNotNull(group, label, pick.stateText()).joinToString(SEPARATOR))
}

private const val TCP = "TCP"
private const val UDP = "UDP"
