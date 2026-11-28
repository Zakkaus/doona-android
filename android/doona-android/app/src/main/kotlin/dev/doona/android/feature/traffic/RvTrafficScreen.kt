package dev.doona.android.feature.traffic

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.doona.android.R
import dev.doona.android.designsystem.rv.LocalFrameInsets
import dev.doona.android.designsystem.rv.RV_NO_VALUE
import dev.doona.android.designsystem.rv.Rv
import dev.doona.android.designsystem.rv.RvAmount
import dev.doona.android.designsystem.rv.RvCard
import dev.doona.android.designsystem.rv.RvChartSize
import dev.doona.android.designsystem.rv.RvDetailChart
import dev.doona.android.designsystem.rv.RvFormat
import dev.doona.android.designsystem.rv.RvPageHeader
import dev.doona.android.designsystem.rv.RvPart
import dev.doona.android.designsystem.rv.RvRangeRow
import dev.doona.android.designsystem.rv.RvScrollPage
import dev.doona.android.designsystem.rv.RvSectionTitle
import dev.doona.android.designsystem.rv.RvSegmented
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.designsystem.rv.RvStatRow
import dev.doona.android.designsystem.rv.RvSteadyCardFigure
import dev.doona.android.designsystem.rv.RvText
import dev.doona.android.designsystem.rv.rememberRvFormat
import dev.doona.android.designsystem.rv.rvGlidingRate

/**
 * The page a traffic tile opens, 流量詳情 as the large title, in the language of Revolut's stock page: 下載 or 上傳
 * picked in the header's control row, each on its own scale; the rate as the hero with small decimals and when it was; the
 * range's chart full-bleed; the range chips (no chart tools: the owner removed them for v0.1, 2026-11-23 21:35); and the
 * connection's figures in a Key stats card. A finger on the chart shows that point in the hero until it lifts. The
 * direction and the range are kept across a rotation; the page reports the range with [onRange] and draws [state]'s
 * chart, which keeps the last one until the range's read arrives, and a new range cross-fades the chart (G5). With no
 * chart read the chart's grid says 尚無資料, as 統計's empty charts do; with no session the card shows a dash for each
 * figure. [initialScrub] starts the page scrubbed to that point, as the scrubbed golden shows it.
 */
@Composable
internal fun RvTrafficScreen(state: RvTrafficState, onRange: (Int) -> Unit, onBack: () -> Unit, initialScrub: Int? = null) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val format = rememberRvFormat()
    val rate: (Float) -> String = { format.rate(it.toDouble()).toString() }
    var upload by rememberSaveable { mutableStateOf(false) }
    var range by rememberSaveable { mutableIntStateOf(RV_TRAFFIC_LIVE) }
    val report by rememberUpdatedState(onRange)
    LaunchedEffect(range) { report(range) }
    val chart = state.chart
    val values = chart?.values(upload).orEmpty()
    // The chosen point is kept by its time, so it stays on its sample as the window moves on.
    val timeAt = { index: Int -> chart?.let { it.endEpochMs - (values.lastIndex - index) * it.stepMs } }
    var selectedTime by remember(upload, range, chart?.range) { mutableStateOf(initialScrub?.takeIf { it in values.indices }?.let(timeAt)) }
    val shown = selectedTime?.let { time -> values.indices.firstOrNull { timeAt(it) == time } }
    val live = if (upload) state.upRate else state.downRate
    val title = stringResource(R.string.rv_traffic_title)
    RvScrollPage(title, insets, onBack = onBack) { titleBlock ->
        val options = listOf(stringResource(R.string.ui_download), stringResource(R.string.ui_upload))
        RvPageHeader(title, titleBlock, insets, control = { RvSegmented(options, if (upload) 1 else 0, onSelect = { upload = it == 1 }) })
        // The rate, its chart and the ranges are one block of the page's column, as 活動's pager is.
        Column(Modifier.testTag(RvPart.BLOCK)) {
            val at = selectedTime?.takeIf { shown != null }?.let { format.clock(it) }
            val sample = shown?.let { values[it].toDouble() }?.takeIf { it.isFinite() }
            val spoken = at?.let { stringResource(R.string.rv_a11y_stat_row, it, stringResource(direction(upload), sample?.let { v -> format.rate(v).toString() } ?: stringResource(R.string.rv_stats_no_data))) }
            Column(Modifier.padding(horizontal = RvSpace.gutter)) {
                // The live rate glides; a scrubbed second shows at once, as the finger moves.
                val glided = rvGlidingRate(live.toDouble().takeIf { state.sampled }, key = upload)
                Hero(if (at == null) glided else sample?.let { format.liveRate(it) } ?: RvAmount(RV_NO_VALUE, ""), at, upload, format)
            }
            Spacer(Modifier.height(RvSpace.chartGap))
            key(upload) {
                RvDetailChart(
                    values,
                    if (upload) Rv.colors.up else Rv.colors.down,
                    rate,
                    shown,
                    onScrub = { selectedTime = it?.let(timeAt) },
                    modifier = if (spoken == null) Modifier else Modifier.semantics { contentDescription = spoken },
                    range = chart?.range ?: range,
                    empty = stringResource(R.string.rv_stats_no_data),
                )
            }
            Spacer(Modifier.height(RvChartSize.pillClear))
            Ranges(range) { range = it }
        }
        Column(Modifier.padding(horizontal = RvSpace.gutter)) {
            RvSectionTitle(stringResource(R.string.rv_this_connection))
            ConnectionCard(state.session, upload, format)
        }
    }
}

/**
 * The rate as a chart card's figure (Revolut's Analytics figure, measured §10.2), then when it was: now, or the scrubbed
 * point's time [at]. A screen reader hears which direction the rate is for (q-a11y-wording), as on 活動.
 */
@Composable
private fun Hero(rate: RvAmount, at: String?, upload: Boolean, format: RvFormat) {
    val spoken = stringResource(direction(upload), rate.toString())
    val widest = remember(format) { format.widestRates() }
    RvSteadyCardFigure(rate, widest, spoken)
    Spacer(Modifier.height(RvSpace.subGap))
    RvText(at ?: stringResource(R.string.rv_now), Rv.type.sub, color = Rv.colors.textSecondary)
}

/** The range chips, the [selected] one raised, with no track behind them, on the gutter. */
@Composable
private fun Ranges(selected: Int, onSelect: (Int) -> Unit) {
    RvRangeRow(RANGES.map { stringResource(it) }, selected, onSelect, Modifier.padding(horizontal = RvSpace.gutter))
}

/**
 * Revolut's Key stats card for this connection: the shown direction's fastest second and its average over the whole
 * session (its bytes over its seconds), the totals each way and how long it has run; a dash for each without a [session].
 */
@Composable
private fun ConnectionCard(session: RvTrafficSession?, upload: Boolean, format: RvFormat) {
    RvCard {
        // The shown direction is picked in the header; a screen reader hears it with each of its figures (q-a11y-wording).
        // honk-gap:traffic-1h
        val peak = if (upload) session?.peakUp else session?.peakDown
        DirectedRow(R.string.rv_stat_peak, peak?.let { format.rate(it).toString() }, upload)
        DirectedRow(R.string.rv_stat_average, session?.let { format.rate(if (upload) it.averageUp else it.averageDown).toString() }, upload)
        RvStatRow(stringResource(R.string.rv_downloaded), session?.let { format.bytes(it.downTotal).toString() } ?: RV_NO_VALUE)
        RvStatRow(stringResource(R.string.rv_uploaded), session?.let { format.bytes(it.upTotal).toString() } ?: RV_NO_VALUE)
        val duration = session?.let { format.duration(it.seconds) }
        RvStatRow(stringResource(R.string.rv_connected_for), duration?.let { stringResource(R.string.rv_duration, it.hours, it.minutes) } ?: RV_NO_VALUE)
    }
}

/** A Key stats row for one direction's [value] (a dash without one), read as 峰值，下載 7.84 MB/s. */
@Composable
private fun DirectedRow(@StringRes label: Int, value: String?, upload: Boolean) {
    val name = stringResource(label)
    val spoken = value?.let { stringResource(R.string.rv_a11y_stat_row, name, stringResource(direction(upload), it)) }
    RvStatRow(name, value ?: RV_NO_VALUE, spoken = spoken)
}

/** How a screen reader names a figure's direction: 下載 512 KB/s or 上傳 512 KB/s. */
private fun direction(upload: Boolean): Int = if (upload) R.string.rv_a11y_upload else R.string.rv_a11y_download

/** The ranges under the chart, in the order of [RV_TRAFFIC_RANGE_SECONDS]. */
private val RANGES: List<Int> = listOf(R.string.rv_range_1m, R.string.rv_range_5m, R.string.rv_range_15m, R.string.rv_range_1h)
