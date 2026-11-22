package dev.doona.android.prototype.rv

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import dev.doona.android.R
import dev.doona.android.designsystem.theme.LocalFrameInsets
import dev.doona.android.feature.NOT_WIRED
import dev.doona.android.feature.activity.ActivityUiState
import dev.doona.android.prototype.rv.data.RvAmount
import dev.doona.android.prototype.rv.data.RvData
import dev.doona.android.prototype.rv.data.RvFormat
import dev.doona.android.prototype.rv.data.RvSession

/**
 * The page a traffic tile opens, 流量詳情 on the bar beside the back disc, in the language of Revolut's stock page: 下載
 * or 上傳 picked at the top, each on its own scale; the rate as the hero with small decimals and when it was; the
 * minute's chart full-bleed; the range chips and two round buttons; and the connection's figures in a Key stats card. A
 * finger on the chart shows that second in the hero until it lifts. v0.1 keeps only the live 60-second window, so 1 分
 * is the one range offered. [initialScrub] starts the page scrubbed to that second, as the scrubbed golden shows it.
 */
@Composable
internal fun RvTrafficScreen(state: ActivityUiState, session: RvSession, onBack: () -> Unit, initialScrub: Int? = null) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val format = rememberRvFormat()
    val rate: (Float) -> String = { format.rate(it.toDouble()).toString() }
    var upload by rememberSaveable { mutableStateOf(false) }
    var scrub by remember { mutableStateOf(initialScrub) }
    val values = if (upload) state.upHistory else state.downHistory
    val live = if (upload) state.upRate else state.downRate
    RvScrollPage(stringResource(R.string.rv_traffic_title), insets, onBack = onBack, compact = true) {
        RvPageTop(insets)
        Column(Modifier.padding(horizontal = RvSpace.gutter)) {
            val options = listOf(stringResource(R.string.ui_download), stringResource(R.string.ui_upload))
            RvSegmented(options, if (upload) 1 else 0, onSelect = { upload = it == 1 })
            Spacer(Modifier.height(RvSpace.chartGap))
            val shown = scrub?.takeIf { it in values.indices && !values[it].isNaN() }
            // A scrubbed second shows its rate and its time; lifting the finger brings back the live rate.
            val at = shown?.let { format.clock(session.nowEpochMs - (values.lastIndex - it) * MS_PER_SECOND) }
            Hero(format.rate((shown?.let { values[it] } ?: live.toFloat()).toDouble()), at)
        }
        Spacer(Modifier.height(RvSpace.chartGap))
        RvDetailChart(values, if (upload) Rv.colors.up else Rv.colors.down, rate, scrub, onScrub = { scrub = it })
        Spacer(Modifier.height(RvChartSize.pillClear))
        Ranges()
        Column(Modifier.padding(horizontal = RvSpace.gutter)) {
            RvSectionTitle(stringResource(R.string.rv_this_connection))
            ConnectionCard(session, upload, format)
        }
        Spacer(Modifier.height(RvSpace.cardGap))
        Spacer(Modifier.windowInsetsBottomHeight(insets))
    }
}

/**
 * The rate as the detail figure (measured §10.1: 33 sp with its decimals, the unit 22 sp), then when it was: now, or the
 * scrubbed second's time [at].
 */
@Composable
private fun Hero(rate: RvAmount, at: String?) {
    RvFigureText(rate.figure(), Rv.type.detailFigure, Rv.type.detailUnit)
    Spacer(Modifier.height(RvSpace.subGap))
    RvText(at ?: stringResource(R.string.rv_now), Rv.type.sub, color = Rv.colors.textSecondary)
}

/** The range chips, with no track behind them, then Revolut's two round buttons ending on the margin (measured §8). */
@Composable
private fun Ranges() {
    Row(Modifier.fillMaxWidth().padding(start = RvSpace.gutter, end = RvSpace.gutter - rvToolOverhang), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(RvChartSize.rangeGap)) {
            RANGES.forEachIndexed { i, label -> RvRangePill(stringResource(label), selected = i == 0, onClick = NOT_WIRED) }
        }
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

/** The ranges under the chart; the live minute, the first, is the one shown. */
private val RANGES: List<Int> = listOf(R.string.rv_range_1m, R.string.rv_range_5m, R.string.rv_range_15m, R.string.rv_range_1h)

private const val MS_PER_SECOND = 1000L

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RvTrafficScreenPreview() {
    val dark = isSystemInDarkTheme()
    RvTheme(rvSystemScheme(dark), dark) { RvTrafficScreen(rvSampleActivity(), RvData.session, onBack = {}) }
}
