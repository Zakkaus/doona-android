package dev.doona.android.feature.activity

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RV_GHOST_DOWN
import dev.doona.android.designsystem.rv.RV_GHOST_UP
import dev.doona.android.designsystem.rv.Rv
import dev.doona.android.designsystem.rv.RvSeries
import dev.doona.android.designsystem.rv.RvSkeletonCard
import dev.doona.android.designsystem.rv.RvSkeletonEnd
import dev.doona.android.designsystem.rv.RvSkeletonRow
import dev.doona.android.designsystem.rv.RvSkeletonSeeAll
import dev.doona.android.designsystem.rv.RvSkeletonTiles
import dev.doona.android.designsystem.rv.RvSparkTile
import dev.doona.android.designsystem.rv.RvTileRow
import dev.doona.android.designsystem.rv.figure
import dev.doona.android.designsystem.rv.rememberRvFormat
import dev.doona.android.engine.Status
import dev.doona.android.feature.model.RvRouteMode
import dev.doona.android.feature.model.fastest

/**
 * 活動's cards while it loads, each part on the lines of what replaces it: the outbound card's rows (出站模式 over the
 * [mode] and its chevron's room; the main [node] over its role and its latency and change; the rule groups, which today's
 * traffic picks, over their bytes) and its see-all line, then the two traffic tiles under their captions. The words the
 * page knows before its data (the mode, the node in use) take their lines, so a name that wraps keeps its row's height.
 */
@Composable
internal fun ActivitySkeleton(mode: RvRouteMode, node: String) {
    RvSkeletonCard {
        RvSkeletonRow(0, chevron = true, title = stringResource(R.string.act_mode), subtitleText = stringResource(mode.label))
        val role = stringResource(if (mode == RvRouteMode.Rule) R.string.rv_main_node else R.string.nav_nodes)
        RvSkeletonRow(1, end = RvSkeletonEnd.Latency, title = node, subtitleText = role, titleKeepWords = false)
        repeat(RULE_ROWS) { RvSkeletonRow(it + 2, end = RvSkeletonEnd.Value) }
        RvSkeletonSeeAll()
    }
    RvSkeletonTiles(listOf(stringResource(R.string.ui_download), stringResource(R.string.ui_upload)))
}

/**
 * Download and upload as two tiles side by side, each on its own scale: the rate, and the last minute's sparkline with
 * that minute's peak, so the label and the curve beside it cover the same window (S01; 流量詳情 marks the same peak on
 * the same minute). The session's peak is shown only where it is labelled 本次. Disconnected, or with no traffic in the
 * window, both tiles say so over ghost bars instead.
 */
@Composable
internal fun TrafficTiles(state: RvActivityState, onOpenTraffic: () -> Unit) {
    val quiet = state.status !is Status.Running || (state.downHistory.all { it == 0f } && state.upHistory.all { it == 0f })
    RvTileRow {
        TrafficTile(R.string.ui_download, R.drawable.rv_sym_arrow_downward, state.downRate, minutePeak(state.downHistory), state.downHistory, Rv.colors.down, quiet, onOpenTraffic, Modifier.weight(1f))
        TrafficTile(R.string.ui_upload, R.drawable.rv_sym_arrow_upward, state.upRate, minutePeak(state.upHistory), state.upHistory, Rv.colors.up, quiet, onOpenTraffic, Modifier.weight(1f), RV_GHOST_UP)
    }
}

@Composable
private fun TrafficTile(
    @StringRes caption: Int,
    @DrawableRes icon: Int,
    rate: Long,
    peak: Float,
    history: List<Float>,
    series: RvSeries,
    quiet: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
    ghost: FloatArray = RV_GHOST_DOWN,
) {
    val format = rememberRvFormat()
    val note = if (quiet) stringResource(R.string.rv_no_traffic) else stringResource(R.string.rv_peak, format.rate(peak.toDouble()).unbroken())
    val widest = remember(format) { format.widestRates().map { it.figure() } }
    RvSparkTile(stringResource(caption), icon, format.rate(rate.toDouble()).figure(), rate.toDouble(), note, history, series, quiet, onClick, modifier, ghost, widest)
}

/** The fastest second of a minute's samples; a missing sample (NaN, a gap in the line) is not a rate. */
private fun minutePeak(history: List<Float>): Float = history.filterNot { it.isNaN() }.maxOrNull() ?: 0f
