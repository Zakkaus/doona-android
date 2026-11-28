package dev.doona.android.designsystem.rv

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.modifiers.TextAutoSizeLayoutScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import kotlin.math.roundToInt

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
 * A tile's live rate ([RvSparkTile]'s sizes) in places that never move: see [RvSteadyFigure]. Where the widest of
 * [widest] does not fit the tile's width (a large font size), number and unit shrink together to the largest step at
 * which it does ([rvFitSize]), so the two tiles side by side, fitting the same widest, take one size.
 */
@Composable
internal fun RvSteadyTileFigure(figure: RvFigure, widest: List<RvFigure>, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val type = Rv.type
    val leadStyle = type.tileValue.copy(color = Rv.colors.text)
    val unitStyle = type.tileUnit.copy(color = Rv.colors.text)
    // Its number and unit read as two texts of the tile, a button that merges them. The slots at full size, in px; the layout shrinks them with the figure. Measured in composition, so the tile's
    // intrinsic height (a [RvTileRow] asks for it) needs no subcomposition.
    val slots = remember(widest, leadStyle, unitStyle, measurer) {
        widest.maxOf { measurer.measure(it.whole, leadStyle).size.width } to widest.maxOf { measurer.measure(it.unit, unitStyle).size.width }
    }
    val (leadSlot, unitSlot) = slots
    val full = leadStyle.fontSize.value
    val total = leadSlot + unitSlot
    Layout(
        {
            BasicText(figure.whole, style = leadStyle, maxLines = 1, autoSize = RvShare(leadStyle.fontSize, total))
            BasicText(figure.unit, style = unitStyle, maxLines = 1, autoSize = RvShare(unitStyle.fontSize, total))
        },
        modifier.testTag(RvPart.TEXT_LINE),
    ) { measurables, constraints ->
        val share = rvFitSize(full, total.toFloat(), constraints.maxWidth.toFloat()) / full
        val free = constraints.copy(minWidth = 0, minHeight = 0)
        val lead = measurables[0].measure(free)
        val unit = measurables[1].measure(free)
        val slot = (leadSlot * share).roundToInt()
        val base = maxOf(lead[FirstBaseline], unit[FirstBaseline])
        val height = maxOf(base - lead[FirstBaseline] + lead.height, base - unit[FirstBaseline] + unit.height)
        layout(constraints.constrainWidth(slot + maxOf((unitSlot * share).roundToInt(), unit.width)), constraints.constrainHeight(height)) {
            lead.place(slot - lead.width, base - lead[FirstBaseline])
            unit.place(slot, base - unit[FirstBaseline])
        }
    }
}

/** [size] shrunk as a line [width] px wide at it shrinks to fit the width given ([rvFitSize]): a tile figure's parts, one share. */
private class RvShare(private val size: TextUnit, private val width: Int) : TextAutoSize {
    override fun TextAutoSizeLayoutScope.getFontSize(constraints: Constraints, text: AnnotatedString): TextUnit =
        size * (rvFitSize(size.value, width.toFloat(), constraints.maxWidth.toFloat()) / size.value)

    override fun equals(other: Any?) = other is RvShare && other.size == size && other.width == width

    override fun hashCode() = 31 * size.hashCode() + width
}

/**
 * The 活動 hero's second line: [icon] before the live rate [amount] in [style] and [color], the figure in places that
 * never move ([RvSteadyFigure]), so the line, centred, keeps its x as the rate gains a digit. A screen reader hears
 * [description].
 */
@Composable
internal fun RvSteadyInline(@DrawableRes icon: Int, amount: RvAmount, widest: List<RvAmount>, color: Color, style: TextStyle, description: String, modifier: Modifier = Modifier) {
    Row(modifier.testTag(RvPart.TEXT_LINE).clearAndSetSemantics { contentDescription = description }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RvSpace.tight)) {
        RvGlyph(icon, RvSize.inlineGlyph, color)
        RvSteadyFigure(amount.number, "", amount.unit, style, style, widest.first().number, widest.map { it.unit }, null, Modifier, color)
    }
}

/**
 * A figure whose places never move as it changes: [lead] set flush right in a slot as wide as [widestLead] (tabular
 * digits, so the decimal point after it stays put and a new leading digit appears inside the slot), then [tail] (the
 * decimal, one digit), then [unit] from a fixed x in a slot as wide as the widest of [units]. The whole is always as
 * wide, so a centred figure does not shift either. One stop for a screen reader, which hears [description]; measured as
 * one line ([RvPart.TEXT_LINE]). With no [description] the caller's line carries both (its glyph is part of the line).
 */
@Composable
private fun RvSteadyFigure(lead: String, tail: String, unit: String, leadStyle: TextStyle, tailStyle: TextStyle, widestLead: String, units: List<String>, description: String?, modifier: Modifier, color: Color = Rv.colors.text) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val leadWidth = remember(widestLead, leadStyle, density) { with(density) { measurer.measure(widestLead, leadStyle).size.width.toDp() } }
    val unitWidth = remember(units, tailStyle, density) { with(density) { units.maxOf { measurer.measure(it, tailStyle).size.width }.toDp() } }
    val line = if (description == null) modifier else modifier.testTag(RvPart.TEXT_LINE).clearAndSetSemantics { contentDescription = description }
    Row(line) {
        BasicText(lead, Modifier.alignByBaseline().width(leadWidth), leadStyle.copy(color = color, textAlign = TextAlign.End), maxLines = 1)
        if (tail.isNotEmpty()) BasicText(tail, Modifier.alignByBaseline(), tailStyle.copy(color = color), maxLines = 1)
        BasicText(unit, Modifier.alignByBaseline().widthIn(min = unitWidth), tailStyle.copy(color = color, textAlign = TextAlign.Start), maxLines = 1)
    }
}
