package dev.doona.android.prototype.rv.stats

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import dev.doona.android.R
import dev.doona.android.prototype.rv.Rv
import dev.doona.android.prototype.rv.RvFigure
import dev.doona.android.prototype.rv.RvGlyph
import dev.doona.android.prototype.rv.RvSize
import dev.doona.android.prototype.rv.RvSpace
import dev.doona.android.prototype.rv.RvText
import dev.doona.android.prototype.rv.rvPress
import dev.doona.android.prototype.rv.scripted
import java.text.NumberFormat
import java.util.Locale

/**
 * A filter chip on the page, Revolut's small action button (`Personal ▾`, `This year`): a 40 dp pill on the container
 * colour, the label, then a chevron that says it opens a choice.
 */
@Composable
internal fun RvStatsChip(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .height(RvStatsSize.chip)
            .clip(CircleShape)
            .background(Rv.colors.segmentTrack)
            .rvPress(CircleShape, Role.Button, onClick)
            .padding(start = RvStatsSize.chipPad, end = RvStatsSize.chipPad - RvStatsSize.chipGlyphGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RvText(label, Rv.type.rowTitle)
        Spacer(Modifier.width(RvStatsSize.chipGlyphGap))
        RvGlyph(R.drawable.rv_stats_ic_expand_more, RvStatsSize.chipGlyph, Rv.colors.textSecondary)
    }
}

/** A card's own header, Revolut's grey label at the top start; [v02] adds the tag that says v0.1 lacks its data. */
@Composable
internal fun RvStatsLabel(text: String, v02: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RvSpace.subGap)) {
        RvText(text, Rv.type.sub, color = Rv.colors.textSecondary)
        if (v02) RvStatsTag()
    }
}

/** The small `v0.2` tag: what the card shows needs data the v0.1 core does not report, so the sample invents it. */
@Composable
private fun RvStatsTag() {
    Box(Modifier.clip(CircleShape).background(Rv.colors.up.disc).padding(horizontal = RvStatsSize.tagPadH, vertical = RvStatsSize.tagPadV)) {
        RvText(V02, Rv.type.pill, color = Rv.colors.up.onDisc)
    }
}

/** The line at a card's foot that names where its data comes from. */
@Composable
internal fun RvStatsSource(text: String, modifier: Modifier = Modifier) {
    RvText(text, Rv.type.tileCaption, modifier, color = Rv.colors.textSecondary)
}

/** A card's figure: the number in [style], its unit in [unitStyle] on the same baseline. */
@Composable
internal fun RvStatsFigure(figure: RvFigure, style: TextStyle = Rv.type.cardValue, unitStyle: TextStyle = Rv.type.cardUnit) {
    val text = buildAnnotatedString {
        append(scripted(figure.whole))
        withStyle(unitStyle.toSpanStyle()) { append(scripted(figure.unit)) }
    }
    BasicText(text, style = style.copy(color = Rv.colors.text), maxLines = 1)
}

/** Revolut's delta: a filled triangle and the change in [color], then what it compares with in secondary ink. */
@Composable
internal fun RvStatsDelta(up: Boolean, change: String, against: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Triangle(up, color, RvStatsSize.delta)
        Spacer(Modifier.width(RvSpace.tight))
        val text = buildAnnotatedString {
            withStyle(SpanStyle(color = color)) { append(change) }
            append(" ")
            append(scripted(against))
        }
        BasicText(text, style = Rv.type.tileCaption.copy(color = Rv.colors.textSecondary), maxLines = 1)
    }
}

@Composable
private fun Triangle(up: Boolean, color: Color, side: Dp) {
    Canvas(Modifier.size(side)) {
        val (w, h) = size.width to size.height
        val path = Path().apply {
            if (up) {
                moveTo(w / 2, 0f)
                lineTo(w, h)
                lineTo(0f, h)
            } else {
                moveTo(0f, 0f)
                lineTo(w, 0f)
                lineTo(w / 2, h)
            }
            close()
        }
        drawPath(path, color)
    }
}

/** A legend entry's rounded square, Revolut's 9 dp. */
@Composable
internal fun RvStatsSwatch(color: Color) {
    Box(Modifier.size(RvStatsSize.legendSquare).clip(RoundedCornerShape(RvStatsSize.legendRadius)).background(color))
}

/** The pager's dots under the tiles: the current one larger and darker, centred under the row. */
@Composable
internal fun RvStatsDots(count: Int, current: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        repeat(count) { i ->
            Box(Modifier.width(RvStatsSize.dotPitch), contentAlignment = Alignment.Center) {
                val size = if (i == current) RvStatsSize.dot else RvStatsSize.dotIdle
                Box(Modifier.size(size).clip(CircleShape).background(if (i == current) Rv.colors.textSecondary else Rv.colors.reference))
            }
        }
    }
}

/** A row's trailing chevron, light grey as Revolut's. */
@Composable
internal fun RvStatsChevron() {
    RvGlyph(R.drawable.rv_sym_chevron_right, RvSize.chevron, Rv.colors.reference)
}

/** A row of the tools card: its glyph, title and subtitle. */
internal class RvStatsTool(@DrawableRes val icon: Int, val title: String, val subtitle: String)

/** Bytes in decimal units as Android sizes files, split so the unit can be set small: `3.44 GB`, `870 MB`. */
internal fun bytesFigure(bytes: Long, locale: Locale, fixed: Boolean = true): RvFigure {
    val (value, unit) = when {
        bytes >= GB -> bytes / GB to " GB"
        bytes >= MB -> bytes / MB to " MB"
        else -> bytes / KB to " kB"
    }
    return RvFigure(decimal(value, locale, fixed), unit)
}

/** How many bytes one of [unit] holds, for a figure set in another figure's unit. */
internal fun bytesPer(unit: String): Double = when (unit) {
    " GB" -> GB
    " MB" -> MB
    else -> KB
}

/** Two decimals under 10, one under 100, none above, as Android rounds a short file size; [fixed] keeps trailing zeros. */
internal fun decimal(value: Double, locale: Locale, fixed: Boolean): String {
    val digits = when {
        value < TEN -> 2
        value < HUNDRED -> 1
        else -> 0
    }
    return NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = if (fixed) digits else 0
        maximumFractionDigits = digits
    }.format(value)
}

private const val GB = 1e9
private const val MB = 1e6
private const val KB = 1e3
private const val TEN = 10.0
private const val HUNDRED = 100.0
private const val V02 = "v0.2"
