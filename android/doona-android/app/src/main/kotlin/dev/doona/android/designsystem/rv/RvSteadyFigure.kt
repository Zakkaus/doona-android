package dev.doona.android.designsystem.rv

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign

/**
 * The 活動 hero's live rate ([RvHero]'s sizes: the integer at 44 sp, the decimal and unit at 32 sp) in places that never
 * move: see [RvSteadyFigure]. [widest] is every widest form the rate takes ([RvFormat.widestRates]); a screen reader
 * hears [description].
 */
@Composable
internal fun RvSteadyHero(amount: RvAmount, widest: List<RvAmount>, description: String, modifier: Modifier = Modifier) {
    val locale = LocalConfiguration.current.locales[0]
    val (whole, decimals) = rvSplitDecimals(amount.number, locale)
    val widestWhole = rvSplitDecimals(widest.first().number, locale).first
    RvSteadyFigure(whole, decimals, amount.unit, Rv.type.hero, Rv.type.heroSmall, widestWhole, widest.map { it.unit }, description, modifier)
}

/** A card's live rate ([RvFigureText]'s card sizes) in places that never move: see [RvSteadyFigure]. */
@Composable
internal fun RvSteadyCardFigure(amount: RvAmount, widest: List<RvAmount>, description: String, modifier: Modifier = Modifier) {
    RvSteadyFigure(amount.number, "", amount.unit, Rv.type.cardValue, Rv.type.cardUnit, widest.first().number, widest.map { it.unit }, description, modifier)
}

/**
 * A figure whose places never move as it changes: [lead] set flush right in a slot as wide as [widestLead] (tabular
 * digits, so the decimal point after it stays put and a new leading digit appears inside the slot), then [tail] (the
 * decimal, one digit), then [unit] from a fixed x in a slot as wide as the widest of [units]. The whole is always as
 * wide, so a centred figure does not shift either. One stop for a screen reader, which hears [description]; measured as
 * one line ([RvPart.TEXT_LINE]).
 */
@Composable
private fun RvSteadyFigure(lead: String, tail: String, unit: String, leadStyle: TextStyle, tailStyle: TextStyle, widestLead: String, units: List<String>, description: String, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val leadWidth = remember(widestLead, leadStyle, density) { with(density) { measurer.measure(widestLead, leadStyle).size.width.toDp() } }
    val unitWidth = remember(units, tailStyle, density) { with(density) { units.maxOf { measurer.measure(it, tailStyle).size.width }.toDp() } }
    val color = Rv.colors.text
    Row(modifier.testTag(RvPart.TEXT_LINE).clearAndSetSemantics { contentDescription = description }) {
        BasicText(lead, Modifier.alignByBaseline().width(leadWidth), leadStyle.copy(color = color, textAlign = TextAlign.End), maxLines = 1)
        if (tail.isNotEmpty()) BasicText(tail, Modifier.alignByBaseline(), tailStyle.copy(color = color), maxLines = 1)
        BasicText(unit, Modifier.alignByBaseline().widthIn(min = unitWidth), tailStyle.copy(color = color, textAlign = TextAlign.Start), maxLines = 1)
    }
}
