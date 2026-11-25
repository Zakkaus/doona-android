package dev.doona.android.designsystem.rv

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.doona.android.R

// What a chosen segment or range chip looks like: a bold label after a check (owner, 2026-11-25, q-a11y-selected).

/** How a row of range chips is set: the one label [style] (bold, the weight of the chosen chip) and whether each chip keeps room for a check. */
internal class RvRangeFit(val style: TextStyle, val check: Boolean) {
    /** What each chip keeps free for the check. */
    val room: Dp = if (check) rvCheckReserve else 0.dp // literal: no check, no room
}

/**
 * The size of a row of range chips in [width]: every label bold, as the chosen one is, and after a check, the widest a
 * chip gets, so choosing never moves a pill. Where the check's room would make the labels smaller than the row needs
 * without it (large text, a long label), the chips go without the check and keep the bold: a label is never cut.
 */
@Composable
internal fun rvRangeFit(labels: List<String>, width: Dp): RvRangeFit {
    val bold = Rv.type.action.rvChosen()
    val plain = rvChipRowFit(labels, bold, RvChartSize.rangePad, RvChartSize.rangeGap, width)
    val checked = rvChipRowFit(labels, bold, RvChartSize.rangePad + rvCheckReserve / 2, RvChartSize.rangeGap, width)
    return if (checked.fontSize >= plain.fontSize) RvRangeFit(checked, check = true) else RvRangeFit(plain, check = false)
}

/** What a chosen label adds before it: the check and its gap. Every choice of a row keeps it free, so choosing moves nothing. */
internal val rvCheckReserve = RvChartSize.segmentCheck + RvChartSize.segmentGlyphGap

/** [this] in the weight of the chosen label: bold, the one cue beside the raised pill's colour (q-a11y-selected). */
internal fun TextStyle.rvChosen(): TextStyle = copy(fontWeight = FontWeight.Bold)

/** How wide [text] is in [bold], so a chip's label box can hold it whichever weight the label has. */
@Composable
internal fun rvBoldWidth(text: String, bold: TextStyle): Dp {
    val measurer = rememberTextMeasurer()
    val px = remember(text, bold, measurer) { measurer.measure(scripted(text), bold, maxLines = 1).size.width }
    return with(LocalDensity.current) { px.toDp() }
}

/**
 * The label of one choice of a segmented control or a range row, centred in [modifier]: when [chosen], in [bold] (the
 * size the whole row was fitted at) after a check; else in the same size at [plain]'s weight. The semantics are the
 * choice's own (selected, role tab), so the check and the weight add nothing for a screen reader to read.
 */
@Composable
internal fun RvChoiceLabel(
    text: String,
    chosen: Boolean,
    bold: TextStyle,
    plain: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    @DrawableRes glyph: Int? = null,
    check: Boolean = true,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        if (chosen && check) {
            RvGlyph(R.drawable.rv_sym_check, RvChartSize.segmentCheck, color)
            Spacer(Modifier.width(RvChartSize.segmentGlyphGap))
        }
        if (glyph != null) {
            RvGlyph(glyph, RvChartSize.segmentGlyph, color)
            Spacer(Modifier.width(RvChartSize.segmentGlyphGap))
        }
        RvText(text, if (chosen) bold else bold.copy(fontWeight = plain.fontWeight), color = color)
    }
}
