package dev.doona.android.feature.stats

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RV_NO_VALUE
import dev.doona.android.designsystem.rv.Rv
import dev.doona.android.designsystem.rv.RvCard
import dev.doona.android.designsystem.rv.RvCardHeader
import dev.doona.android.designsystem.rv.RvFigure
import dev.doona.android.designsystem.rv.RvMeter
import dev.doona.android.designsystem.rv.RvText
import dev.doona.android.designsystem.rv.SEPARATOR
import dev.doona.android.feature.model.RvNode
import dev.doona.android.feature.model.RvScorePick
import dev.doona.android.feature.nodes.ui.RvScoreChoiceRow
import dev.doona.android.feature.nodes.ui.stateText
import java.text.NumberFormat

/**
 * 綜合評分, in 分流比例's grammar: the connections the Score groups started as the figure ([RvStatsScore.starts]), and a
 * bar each for the trials honk ran to compare their nodes that succeeded and failed, their sum as the header's tag, as
 * the group page's cards carry their coverage (cancelled trials have no result and are left out). Then, per group and network, the node it chose with its state, as the group
 * page's first row draws it, its line beginning with the group's name when there are several. honk's native API reports
 * no Score counters, so the figure, the bars' counts and the tag's count show 「—」 over empty bars (owner, 2026-11-27,
 * keep-1127); the choices are honk's.
 */
@Composable
internal fun RvScoreStatsCard(score: RvStatsScore) {
    val locale = LocalConfiguration.current.locales[0]
    val count = NumberFormat.getIntegerInstance(locale)
    val ok = score.trialsOk
    val failed = score.trialsFailed
    val trials = if (ok != null && failed != null) ok + failed else null
    val share = if (ok != null && trials != null && trials > 0) ok.toFloat() / trials else 0f
    val known = trials == null || trials > 0
    fun shown(value: Long?) = value?.let(count::format) ?: RV_NO_VALUE
    RvCard {
        RvCardHeader(stringResource(R.string.policy_kind_score), tag = pluralStringResource(R.plurals.rv_score_trials, (trials ?: 0L).toInt(), shown(trials)))
        Column(cardBody) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    RvStatsFigure(RvFigure(shown(score.starts), ""))
                    RvText(stringResource(R.string.rv_stats_connections), Rv.type.rowSub, color = Rv.colors.textSecondary)
                }
                Column(Modifier.weight(1f)) {
                    RvMeter(share, shown(ok), stringResource(R.string.rv_score_trial_ok), Rv.colors.down.line, known)
                    RvMeter(if (trials == null) 0f else 1f - share, shown(failed), stringResource(R.string.rv_score_trial_failed), Rv.colors.textSecondary, known)
                }
            }
        }
        // The group's name begins the line only when there is more than one group to tell apart.
        val named = score.groups.size > 1
        for (group in score.groups) {
            val name = group.name.takeIf { named }
            Choice(name, TCP, group.tcp, group.members)
            Choice(name, UDP, group.udp, group.members)
        }
    }
}

/** `TCP / 已選定` (`香港 / TCP / 已選定` with the [group]'s name) under the node the group chose on that network. */
@Composable
private fun Choice(group: String?, label: String, pick: RvScorePick, members: List<RvNode>) {
    RvScoreChoiceRow(pick, members, listOfNotNull(group, label, pick.stateText()).joinToString(SEPARATOR))
}

private const val TCP = "TCP"
private const val UDP = "UDP"
