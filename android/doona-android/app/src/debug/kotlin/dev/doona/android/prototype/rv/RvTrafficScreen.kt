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
import androidx.compose.foundation.layout.width
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
import java.text.DecimalFormatSymbols
import java.util.Locale

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
        RvBarSpacer(insets)
        Spacer(Modifier.height(RvSpace.gutter))
        Column(Modifier.padding(horizontal = RvSpace.gutter)) {
            val options = listOf(stringResource(R.string.ui_download), stringResource(R.string.ui_upload))
            RvSegmented(options, if (upload) 1 else 0, onSelect = { upload = it == 1 })
            Spacer(Modifier.height(RvSpace.chartGap))
            val shown = scrub?.takeIf { it in values.indices }
            Hero(format.rate((shown?.let { values[it] } ?: live.toFloat()).toDouble()), shown?.let { values.size - 1 - it } ?: 0, format.locale)
        }
        Spacer(Modifier.height(RvSpace.chartGap))
        RvDetailChart(values, if (upload) Rv.colors.up else Rv.colors.down, rate, scrub, onScrub = { scrub = it })
        Spacer(Modifier.height(RvSpace.chipGap))
        Ranges()
        Spacer(Modifier.height(RvSpace.sectionGap))
        Column(Modifier.padding(horizontal = RvSpace.gutter)) {
            RvText(stringResource(R.string.rv_this_connection), Rv.type.section)
            Spacer(Modifier.height(RvSpace.headingGap))
            ConnectionCard(session, upload, format)
        }
        Spacer(Modifier.height(RvSpace.cardGap))
        Spacer(Modifier.windowInsetsBottomHeight(insets))
    }
}

/** The rate as the hero, its decimals small, then when it was: now, or [secondsAgo] seconds before now. */
@Composable
private fun Hero(rate: RvAmount, secondsAgo: Int, locale: Locale) {
    val (whole, decimals) = heroParts(rate.number, locale)
    RvHero(whole, decimals + rate.unit)
    Spacer(Modifier.height(RvSpace.subGap))
    val line = if (secondsAgo == 0) stringResource(R.string.rv_now) else stringResource(R.string.rv_seconds_ago, secondsAgo)
    RvText(line, Rv.type.sub, color = Rv.colors.textSecondary)
}

/** The range chips, then Revolut's two round buttons at the end of the row. */
@Composable
private fun Ranges() {
    Row(Modifier.fillMaxWidth().padding(horizontal = RvSpace.gutter), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(RvChartSize.rangeGap)) {
            RANGES.forEach { (label, offered) -> RvRangePill(stringResource(label), selected = offered, enabled = offered, onClick = NOT_WIRED) }
        }
        RvRoundButton(R.drawable.rv_sym_candlestick_chart, stringResource(R.string.rv_chart_type), NOT_WIRED)
        Spacer(Modifier.width(RvSpace.tight))
        RvRoundButton(R.drawable.rv_sym_open_in_full, stringResource(R.string.rv_full_screen), NOT_WIRED)
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

/** A figure split at its decimal separator, so the decimals can be set small as Revolut sets a price. */
private fun heroParts(figure: String, locale: Locale): Pair<String, String> {
    val cut = figure.indexOf(DecimalFormatSymbols.getInstance(locale).decimalSeparator)
    return if (cut < 0) figure to "" else figure.substring(0, cut) to figure.substring(cut)
}

/** The ranges under the chart, and whether v0.1 offers each: only the live minute. */
private val RANGES: List<Pair<Int, Boolean>> = listOf(
    R.string.rv_range_1m to true,
    R.string.rv_range_5m to false,
    R.string.rv_range_15m to false,
    R.string.rv_range_1h to false,
)

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RvTrafficScreenPreview() {
    val dark = isSystemInDarkTheme()
    RvTheme(rvSystemScheme(dark), dark) { RvTrafficScreen(rvSampleActivity(), RvData.session, onBack = {}) }
}
