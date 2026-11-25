package dev.doona.android.prototype.rv.stats

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.doona.android.R
import dev.doona.android.designsystem.rv.Rv
import dev.doona.android.designsystem.rv.RvCard
import dev.doona.android.designsystem.rv.RvCardHeader
import dev.doona.android.designsystem.rv.RvFigure
import dev.doona.android.designsystem.rv.RvText
import dev.doona.android.engine.ScoreNetwork
import dev.doona.android.engine.ScoreState
import dev.doona.android.feature.model.RvNode
import dev.doona.android.prototype.rv.nodes.SEPARATOR
import dev.doona.android.prototype.rv.policy.RvPolicyGroup
import dev.doona.android.prototype.rv.policy.RvScoreChoiceRow
import dev.doona.android.prototype.rv.policy.stateText
import java.text.NumberFormat

/** What the Score card reads: honk's `scoreState`, and each Score group's name and members as the group page has them. */
internal class RvStatsScore(val state: ScoreState, val groups: List<RvPolicyGroup>)

/**
 * 綜合評分, in 分流比例's grammar: the connections the Score groups started as the figure (`rootBusinessStarts`), and a
 * bar each for the trials honk ran to compare their nodes that succeeded and failed, their sum as the header's tag, as
 * the group page's cards carry their coverage (cancelled trials have no result and are left out). Then, per group and network, the node it chose with its state, as the group
 * page's first row draws it, its line beginning with the group's name when there are several. honk keeps these counters
 * for the running process and no time of the last change, so the card shows no history.
 */
@Composable
internal fun RvScoreStatsCard(score: RvStatsScore) {
    val locale = LocalConfiguration.current.locales[0]
    val count = NumberFormat.getIntegerInstance(locale)
    val networks = score.state.groups.flatMap { listOf(it.tcp, it.udp) }
    val ok = networks.sumOf { it.counters.trialSuccess }
    val failed = networks.sumOf { it.counters.trialFailure }
    val trials = ok + failed
    val share = if (trials > 0) ok.toFloat() / trials else 0f
    RvCard {
        RvCardHeader(stringResource(R.string.policy_kind_score), tag = pluralStringResource(R.plurals.rv_score_trials, trials.toInt(), trials.toInt()))
        Column(cardBody) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    RvStatsFigure(RvFigure(count.format(score.state.rootBusinessStarts), ""))
                    RvText(stringResource(R.string.rv_stats_connections), Rv.type.rowSub, color = Rv.colors.textSecondary)
                }
                Column(Modifier.weight(1f)) {
                    RvRatingRow(share, count.format(ok), stringResource(R.string.rv_score_trial_ok), Rv.colors.down.line, trials > 0)
                    RvRatingRow(1f - share, count.format(failed), stringResource(R.string.rv_score_trial_failed), Rv.colors.textSecondary, trials > 0)
                }
            }
        }
        // The group's name begins the line only when there is more than one group to tell apart.
        val named = score.state.groups.size > 1
        for (group in score.state.groups) {
            val known = score.groups.firstOrNull { it.id == group.id }
            val name = if (named) known?.let { stringResource(it.name) } ?: group.id else null
            val members = known?.members.orEmpty()
            Choice(name, TCP, group.tcp, members)
            Choice(name, UDP, group.udp, members)
        }
    }
}

/** `TCP · 已選定` (`香港 · TCP · 已選定` with the [group]'s name) under the node the group chose on that network. */
@Composable
private fun Choice(group: String?, label: String, network: ScoreNetwork, members: List<RvNode>) {
    RvScoreChoiceRow(network, members, listOfNotNull(group, label, network.stateText()).joinToString(SEPARATOR))
}

private const val TCP = "TCP"
private const val UDP = "UDP"
