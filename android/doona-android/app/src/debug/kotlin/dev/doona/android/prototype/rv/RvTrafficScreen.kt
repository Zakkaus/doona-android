package dev.doona.android.prototype.rv

import android.content.res.Configuration
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.snap
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import dev.doona.android.R
import dev.doona.android.designsystem.rv.Rv
import dev.doona.android.designsystem.rv.RvAmount
import dev.doona.android.designsystem.rv.RvCard
import dev.doona.android.designsystem.rv.RvChartSize
import dev.doona.android.designsystem.rv.RvChartTools
import dev.doona.android.designsystem.rv.RvDetailChart
import dev.doona.android.designsystem.rv.RvFigure
import dev.doona.android.designsystem.rv.RvFigureText
import dev.doona.android.designsystem.rv.RvFormat
import dev.doona.android.designsystem.rv.RvMotion
import dev.doona.android.designsystem.rv.RvPageHeader
import dev.doona.android.designsystem.rv.RvPart
import dev.doona.android.designsystem.rv.RvRangeRow
import dev.doona.android.designsystem.rv.RvScrollPage
import dev.doona.android.designsystem.rv.RvSectionTitle
import dev.doona.android.designsystem.rv.RvSegmented
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.designsystem.rv.RvStatRow
import dev.doona.android.designsystem.rv.RvText
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.designsystem.rv.rememberRvFormat
import dev.doona.android.designsystem.rv.rvSystemScheme
import dev.doona.android.designsystem.rv.rvToolOverhang
import dev.doona.android.designsystem.theme.LocalFrameInsets
import dev.doona.android.feature.activity.ActivityUiState
import dev.doona.android.prototype.rv.data.RV_RANGE_POINTS
import dev.doona.android.prototype.rv.data.RV_RANGE_SECONDS
import dev.doona.android.prototype.rv.data.RvData
import dev.doona.android.prototype.rv.data.RvSession
import dev.doona.android.prototype.rv.data.RvSessionSeconds

/**
 * The page a traffic tile opens, 流量詳情 as the large title, in the language of Revolut's stock page: 下載 or 上傳
 * picked in the header's control row, each on its own scale; the rate as the hero with small decimals and when it was; the
 * range's chart full-bleed; the range chips and two round buttons; and the connection's figures in a Key stats card. A
 * finger on the chart shows that point in the hero until it lifts. 1 分 is the live minute; 5 分, 15 分 and 1 小時 are
 * [RvSessionSeconds]' windows ending now, derived from [session], and a new range cross-fades the chart (G5).
 * [initialScrub] starts the page scrubbed to that second, as the scrubbed golden shows it.
 */
@Composable
internal fun RvTrafficScreen(state: ActivityUiState, session: RvSession, onBack: () -> Unit, initialScrub: Int? = null) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val format = rememberRvFormat()
    val rate: (Float) -> String = { format.rate(it.toDouble()).toString() }
    var upload by rememberSaveable { mutableStateOf(false) }
    var range by rememberSaveable { mutableIntStateOf(0) }
    var scrub by remember { mutableStateOf(initialScrub) }
    val seconds = remember(session) { RvSessionSeconds(session) }
    val values = when {
        range > 0 -> seconds.chart(range, upload)
        upload -> state.upHistory
        else -> state.downHistory
    }
    // How long one point of the chart spans: a second over 1 分, a minute over 1 小時.
    val step = RV_RANGE_SECONDS[range] / RV_RANGE_POINTS * MS_PER_SECOND
    val live = if (upload) state.upRate else state.downRate
    val title = stringResource(R.string.rv_traffic_title)
    RvScrollPage(title, insets, onBack = onBack) { titleBlock ->
        val options = listOf(stringResource(R.string.ui_download), stringResource(R.string.ui_upload))
        RvPageHeader(title, titleBlock, insets, control = { RvSegmented(options, if (upload) 1 else 0, onSelect = { upload = it == 1 }) })
        // The rate, its chart and the ranges are one block of the page's column, as 活動's pager is.
        Column(Modifier.testTag(RvPart.BLOCK)) {
            Column(Modifier.padding(horizontal = RvSpace.gutter)) {
                val shown = scrub?.takeIf { it in values.indices && !values[it].isNaN() }
                // A scrubbed point shows its rate and when it ended; lifting the finger brings back the live rate.
                val at = shown?.let { format.clock(session.nowEpochMs - (values.lastIndex - it) * step) }
                Hero(format.rate((shown?.let { values[it] } ?: live.toFloat()).toDouble()), at)
            }
            Spacer(Modifier.height(RvSpace.chartGap))
            RvDetailChart(values, if (upload) Rv.colors.up else Rv.colors.down, rate, scrub, onScrub = { scrub = it }, range = range)
            Spacer(Modifier.height(RvChartSize.pillClear))
            Ranges(range) { range = it }
        }
        Column(Modifier.padding(horizontal = RvSpace.gutter)) {
            RvSectionTitle(stringResource(R.string.rv_this_connection))
            ConnectionCard(session, upload, format)
        }
        Spacer(Modifier.height(RvSpace.cardGap))
        Spacer(Modifier.windowInsetsBottomHeight(insets))
    }
}

/**
 * The rate as a chart card's figure (Revolut's Analytics figure, measured §10.2), then when it was: now, or the scrubbed
 * second's time [at].
 */
@Composable
private fun Hero(rate: RvAmount, at: String?) {
    // Each second's live rate cross-fades in; a scrubbed second follows the finger at once.
    val fade = if (at == null) Rv.motion.tween<Float>(RvMotion.FADE_MS) else snap()
    Crossfade(rate.number to rate.unit, animationSpec = fade, label = "rate") { (number, unit) -> RvFigureText(RvFigure(number, unit), Rv.type.cardValue, Rv.type.cardUnit) }
    Spacer(Modifier.height(RvSpace.subGap))
    RvText(at ?: stringResource(R.string.rv_now), Rv.type.sub, color = Rv.colors.textSecondary)
}

/**
 * The range chips, the [selected] one raised, with no track behind them, then Revolut's two round buttons ending on the
 * margin (measured §8).
 */
@Composable
private fun Ranges(selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = RvSpace.gutter, end = RvSpace.gutter - rvToolOverhang), verticalAlignment = Alignment.CenterVertically) {
        RvRangeRow(RANGES.map { stringResource(it) }, selected, onSelect, Modifier.weight(1f))
        RvChartTools(R.drawable.rv_sym_candlestick_chart to stringResource(R.string.rv_chart_type), R.drawable.rv_sym_open_in_full to stringResource(R.string.rv_full_screen))
    }
}

/**
 * Revolut's Key stats card for this connection: the shown direction's fastest second and its average over the whole
 * session (its bytes over its seconds), the totals each way and how long it has run.
 */
@Composable
private fun ConnectionCard(session: RvSession, upload: Boolean, format: RvFormat) {
    val duration = format.duration(session.seconds)
    RvCard {
        RvStatRow(stringResource(R.string.rv_stat_peak), format.rate((if (upload) session.peakUp else session.peakDown).toDouble()).toString())
        RvStatRow(stringResource(R.string.rv_stat_average), format.rate(if (upload) session.averageUp else session.averageDown).toString())
        RvStatRow(stringResource(R.string.rv_downloaded), format.bytes(session.downTotal).toString())
        RvStatRow(stringResource(R.string.rv_uploaded), format.bytes(session.upTotal).toString())
        RvStatRow(stringResource(R.string.rv_connected_for), stringResource(R.string.rv_duration, duration.hours, duration.minutes))
    }
}

/** The ranges under the chart, in the order of [RV_RANGE_SECONDS]. */
private val RANGES: List<Int> = listOf(R.string.rv_range_1m, R.string.rv_range_5m, R.string.rv_range_15m, R.string.rv_range_1h)

private const val MS_PER_SECOND = 1000L

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RvTrafficScreenPreview() {
    val dark = isSystemInDarkTheme()
    RvTheme(rvSystemScheme(dark), dark) { RvTrafficScreen(rvSampleActivity(), RvData.session, onBack = {}) }
}
