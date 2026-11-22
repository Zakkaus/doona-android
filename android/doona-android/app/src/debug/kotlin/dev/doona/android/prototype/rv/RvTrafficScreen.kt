package dev.doona.android.prototype.rv

import android.content.res.Configuration
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import dev.doona.android.R
import dev.doona.android.designsystem.theme.LocalFrameInsets
import dev.doona.android.feature.NOT_WIRED
import dev.doona.android.feature.activity.ActivityUiState
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.util.Locale

/**
 * The page a traffic tile opens, in the language of Revolut's stock page: 下載 or 上傳 picked at the top, each on its own
 * scale; the rate as the hero with small decimals and when it was; the minute's chart full-bleed; the range chips and
 * two round buttons; and the connection's figures in a Key stats card. A finger on the chart shows that second in the
 * hero until it lifts. v0.1 keeps only the live 60-second window, so 1 分 is the one range offered. [initialScrub] starts
 * the page scrubbed to that second, as the scrubbed golden shows it.
 */
@Composable
internal fun RvTrafficScreen(state: ActivityUiState, connection: RvConnection, onBack: () -> Unit, initialScrub: Int? = null) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val rate = rateFormat()
    var upload by rememberSaveable { mutableStateOf(false) }
    var scrub by remember { mutableStateOf(initialScrub) }
    val values = if (upload) state.upHistory else state.downHistory
    val live = if (upload) state.upRate else state.downRate
    Column(Modifier.fillMaxSize().background(Rv.colors.page).verticalScroll(rememberScrollState())) {
        Spacer(Modifier.windowInsetsTopHeight(insets))
        RvBackBar(onBack, R.drawable.rv_sym_arrow_back)
        Column(Modifier.padding(horizontal = RvSpace.gutter)) {
            val options = listOf(stringResource(R.string.ui_download), stringResource(R.string.ui_upload))
            RvSegmented(options, if (upload) 1 else 0, onSelect = { upload = it == 1 })
            Spacer(Modifier.height(RvSpace.chartGap))
            val shown = scrub?.takeIf { it in values.indices }
            Hero(shown?.let { values[it] } ?: live.toFloat(), shown?.let { values.size - 1 - it } ?: 0, rate.number)
        }
        Spacer(Modifier.height(RvSpace.chartGap))
        RvDetailChart(values, if (upload) Rv.colors.up else Rv.colors.down, rate.label, scrub, onScrub = { scrub = it })
        Spacer(Modifier.height(RvSpace.chipGap))
        Ranges()
        Spacer(Modifier.height(RvSpace.sectionGap))
        Column(Modifier.padding(horizontal = RvSpace.gutter)) {
            RvText(stringResource(R.string.rv_this_connection), Rv.type.section)
            Spacer(Modifier.height(RvSpace.headingGap))
            ConnectionCard(values, connection, rate.label)
        }
        Spacer(Modifier.height(RvSpace.cardGap))
        Spacer(Modifier.windowInsetsBottomHeight(insets))
    }
}

/** The rate as the hero, its decimals small, then when it was: now, or [secondsAgo] seconds before now. */
@Composable
private fun Hero(bytesPerSecond: Float, secondsAgo: Int, number: NumberFormat) {
    val locale = LocalConfiguration.current.locales[0]
    val (whole, decimals) = heroParts(number.format(bytesPerSecond / BYTES_PER_MB), locale)
    RvHero(whole, decimals + MB_PER_SECOND)
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
        RvRoundButton(R.drawable.rv_sym_bar_chart, stringResource(R.string.rv_chart_type), NOT_WIRED)
        Spacer(Modifier.width(RvSpace.tight))
        RvRoundButton(R.drawable.rv_sym_open_in_full, stringResource(R.string.rv_full_screen), NOT_WIRED)
    }
}

/** Revolut's Key stats card for this connection: the shown direction's peak and average, the totals and how long. */
@Composable
private fun ConnectionCard(values: List<Float>, connection: RvConnection, rate: (Float) -> String) {
    val context = LocalContext.current
    val hours = connection.seconds / SECONDS_PER_HOUR
    val minutes = connection.seconds % SECONDS_PER_HOUR / SECONDS_PER_MINUTE
    RvCard {
        RvStatRow(stringResource(R.string.rv_stat_peak), rate(values.maxOrNull() ?: 0f))
        RvStatRow(stringResource(R.string.rv_stat_average), rate(if (values.isEmpty()) 0f else values.average().toFloat()))
        RvStatRow(stringResource(R.string.rv_downloaded), Formatter.formatShortFileSize(context, connection.downTotal))
        RvStatRow(stringResource(R.string.rv_uploaded), Formatter.formatShortFileSize(context, connection.upTotal))
        RvStatRow(stringResource(R.string.rv_connected_for), stringResource(R.string.rv_duration, hours, minutes))
    }
}

/** The page's number format, two decimals in the locale's style, and a rate in it in MB/s. */
private class RateFormat(val number: NumberFormat) {
    val label: (Float) -> String = { bytesPerSecond -> number.format(bytesPerSecond / BYTES_PER_MB) + MB_PER_SECOND }
}

@Composable
private fun rateFormat(): RateFormat {
    val locale = LocalConfiguration.current.locales[0]
    return remember(locale) {
        RateFormat(
            NumberFormat.getNumberInstance(locale).apply {
                minimumFractionDigits = DECIMALS
                maximumFractionDigits = DECIMALS
            },
        )
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

/** Android's file sizes are decimal (formatShortFileSize), so the page's rates are too, all in MB/s. */
private const val BYTES_PER_MB = 1_000_000f
private const val MB_PER_SECOND = " MB/s"

/** Two decimals, as Revolut prices a stock: the hero's small part and every rate on the page. */
private const val DECIMALS = 2
private const val SECONDS_PER_HOUR = 3_600
private const val SECONDS_PER_MINUTE = 60

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RvTrafficScreenPreview() {
    val dark = isSystemInDarkTheme()
    RvTheme(rvSystemScheme(dark), dark) { RvTrafficScreen(rvSampleActivity(), RV_SAMPLE_CONNECTION, onBack = {}) }
}
