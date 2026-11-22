package dev.doona.android.prototype.rv

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import dev.doona.android.R
import dev.doona.android.designsystem.theme.LocalFrameInsets
import dev.doona.android.feature.NOT_WIRED
import dev.doona.android.feature.activity.ActivityUiState
import dev.doona.android.feature.activity.sampleActivityState
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.util.Locale

/**
 * The page a traffic tile opens, in the language of Revolut's stock page: the download rate as the hero with small
 * decimals, its peak and average over the window, the minute's chart full-bleed, a key to its two lines, and the range
 * pills. v0.1 keeps only the live 60-second window, so 1 分 is the one range offered. It renders [ActivityUiState].
 */
@Composable
internal fun RvTrafficScreen(state: ActivityUiState, onBack: () -> Unit) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val locale = LocalConfiguration.current.locales[0]
    val number = remember(locale) {
        NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = DECIMALS
            maximumFractionDigits = DECIMALS
        }
    }
    val rate = { bytesPerSecond: Float -> number.format(bytesPerSecond / BYTES_PER_MB) + MB_PER_SECOND }
    Column(Modifier.fillMaxSize().background(Rv.colors.page).verticalScroll(rememberScrollState())) {
        Spacer(Modifier.windowInsetsTopHeight(insets))
        RvBackBar(onBack)
        Column(Modifier.padding(horizontal = RvSpace.gutter)) {
            RvText(stringResource(R.string.ui_download), Rv.type.caption, color = Rv.colors.textSecondary)
            Spacer(Modifier.height(RvSpace.tight))
            val (whole, decimals) = heroParts(number.format(state.downRate / BYTES_PER_MB), locale)
            RvHero(whole, decimals + MB_PER_SECOND)
            Spacer(Modifier.height(RvSpace.subGap))
            val history = state.downHistory
            RvText(stringResource(R.string.rv_peak, rate(history.maxOrNull() ?: 0f)), Rv.type.sub, color = Rv.colors.textSecondary)
            val average = if (history.isEmpty()) 0f else history.average().toFloat()
            RvText(stringResource(R.string.rv_average, rate(average)), Rv.type.sub, color = Rv.colors.textSecondary)
        }
        Spacer(Modifier.height(RvSpace.chartGap))
        RvDetailChart(state.downHistory, state.upHistory, rate)
        Spacer(Modifier.height(RvSpace.subGap))
        Row(Modifier.padding(horizontal = RvSpace.gutter), horizontalArrangement = Arrangement.spacedBy(RvSpace.rowPadding)) {
            Key(stringResource(R.string.ui_download), rate(state.downRate.toFloat()), Rv.colors.down.line)
            Key(stringResource(R.string.ui_upload), rate(state.upRate.toFloat()), Rv.colors.up.line)
        }
        Spacer(Modifier.height(RvSpace.chartGap))
        Row(Modifier.padding(horizontal = RvSpace.gutter), horizontalArrangement = Arrangement.spacedBy(RvSpace.pillGap)) {
            RANGES.forEach { (label, offered) -> RvRangePill(stringResource(label), selected = offered, enabled = offered, onClick = NOT_WIRED) }
        }
        Spacer(Modifier.height(RvSpace.cardGap))
        Spacer(Modifier.windowInsetsBottomHeight(insets))
    }
}

/** A line's key: a dot in its colour, then its name and current rate. */
@Composable
private fun Key(label: String, value: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RvSpace.subGap)) {
        Box(Modifier.size(RvSize.dot).clip(CircleShape).background(color))
        RvText("$label $value", Rv.type.rowSub, color = Rv.colors.textSecondary)
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

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RvTrafficScreenPreview() {
    val dark = isSystemInDarkTheme()
    RvTheme(rvSystemScheme(dark), dark) { RvTrafficScreen(sampleActivityState(), onBack = {}) }
}
