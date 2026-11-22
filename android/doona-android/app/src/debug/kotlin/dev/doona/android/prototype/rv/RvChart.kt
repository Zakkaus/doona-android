package dev.doona.android.prototype.rv

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import kotlin.math.roundToInt

/** Where values sit in a chart's canvas, in pixels: 0 on [bottom], [high] on [top]. */
private class Scale(val high: Float, val top: Float, val bottom: Float) {
    fun y(value: Float) = if (high <= 0f) bottom else bottom - (bottom - top) * (value / high).coerceIn(0f, 1f)
}

/** A tile's figure: the number large, its unit small on the same baseline. */
internal class RvFigure(val whole: String, val unit: String)

/**
 * One of Revolut's crypto home tiles, square as they are: [caption] at the top start, [icon] in the series' tinted 20 dp
 * disc at the top end, the [figure] in bold, a secondary [note] under it, and the sparkline of [values] at the bottom
 * (measured §10.2: 148 x 60 dp). With [quiet] (no
 * connection, or no traffic) the tile takes Revolut's empty chart card instead: a larger figure over ghost bars, and
 * [note] says why there is no line. The whole tile is one button.
 */
@Composable
internal fun RvSparkTile(
    caption: String,
    @DrawableRes icon: Int,
    figure: RvFigure,
    note: String,
    values: List<Float>,
    series: RvSeries,
    quiet: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val type = Rv.type
    RvCard(modifier.aspectRatio(1f).rvPress(RoundedCornerShape(RvSize.cardRadius), Role.Button, onClick)) {
        Column(Modifier.fillMaxSize().padding(RvChartSize.tilePad)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RvText(caption, if (quiet) type.rowSub else type.tileCaption, Modifier.weight(1f), color = Rv.colors.textSecondary)
                Box(Modifier.size(RvChartSize.tileDisc).clip(CircleShape).background(series.disc), contentAlignment = Alignment.Center) {
                    RvGlyph(icon, RvChartSize.tileGlyph, series.onDisc)
                }
            }
            Spacer(Modifier.height(RvSpace.tight))
            RvFigureText(figure, if (quiet) type.cardValue else type.tileValue, if (quiet) type.cardUnit else type.tileUnit)
            RvText(note, type.tileCaption, color = Rv.colors.textSecondary)
            Spacer(Modifier.weight(1f))
            if (quiet) RvGhostBars() else RvSparkline(values, series.line)
        }
    }
}

/** A figure in [style], its unit in [unitStyle] on the same baseline; Latin only, as figures and units are. */
@Composable
internal fun RvFigureText(figure: RvFigure, style: TextStyle, unitStyle: TextStyle, modifier: Modifier = Modifier) {
    val text = buildAnnotatedString {
        append(figure.whole)
        withStyle(unitStyle.toSpanStyle()) { append(figure.unit) }
    }
    BasicText(text, modifier, style = style.copy(color = Rv.colors.text), maxLines = 1)
}

/**
 * A tile's sparkline, as Revolut's crypto tiles draw one: [values] as a 2 dp line in [color] from zero to 10 % above
 * their peak, over a neutral grey fill that fades to nothing at the bottom, and a light grey dotted line at the opening
 * value, the window's first. No axes and no labels.
 */
@Composable
internal fun RvSparkline(values: List<Float>, color: Color, modifier: Modifier = Modifier) {
    val colors = Rv.colors
    Canvas(modifier.fillMaxWidth().height(RvChartSize.spark)) {
        if (values.size < 2) return@Canvas
        val stroke = RvChartSize.sparkStroke.toPx()
        val scale = Scale(values.max() * RvChartSize.SPARK_HEADROOM, 0f, size.height - stroke / 2)
        val line = polyline(values, scale, size.width)
        drawPath(areaUnder(line, size.width), Brush.verticalGradient(listOf(colors.tileFill, Color.Transparent), scale.y(values.max()), size.height))
        dottedAcross(colors.reference, scale.y(values.first()))
        drawPath(line, color, style = lineStroke(stroke))
    }
}

/** Revolut's empty 'Income' card: twelve faint bars of made-up heights across the width, each fading out downward. */
@Composable
internal fun RvGhostBars(modifier: Modifier = Modifier) {
    val ghost = Rv.colors.ghost
    Canvas(modifier.fillMaxWidth().height(RvChartSize.spark)) {
        val bar = RvChartSize.ghostBar.toPx()
        val pitch = (size.width - bar) / (RvChartSize.GHOST_BARS - 1)
        GHOST_HEIGHTS.forEachIndexed { i, height ->
            val top = size.height * (1 - height)
            val brush = Brush.verticalGradient(listOf(ghost, Color.Transparent), top, size.height)
            drawRoundRect(brush, Offset(i * pitch, top), Size(bar, size.height - top), CornerRadius(bar / 2))
        }
    }
}

/**
 * Revolut's stock chart, full-bleed: [values] as a line in [series]'s colour on an axis from zero to peak × 1.15, over a
 * tint that fades out well above the bottom, on a hairline grid. A dotted line marks the peak, with its value ([label])
 * in a pill at the right edge; the low has a pill at the bottom right, and the start value a see-through pill at the
 * left edge. The live point at the end carries a soft halo. A finger on the chart moves [scrub] to the second under it
 * and shows a line and a dot there; lifting it calls [onScrub] with null.
 */
@Composable
internal fun RvDetailChart(values: List<Float>, series: RvSeries, label: (Float) -> String, scrub: Int?, onScrub: (Int?) -> Unit, modifier: Modifier = Modifier) {
    val colors = Rv.colors
    val pillStyle = Rv.type.pill.copy(color = colors.onPill)
    val measurer = rememberTextMeasurer()
    Canvas(modifier.fillMaxWidth().height(RvChartSize.detail).scrubbing(values.size, onScrub)) {
        hairlineGrid(colors.grid)
        if (values.size < 2) return@Canvas
        val stroke = RvChartSize.detailStroke.toPx()
        val peak = values.max()
        val scale = Scale(peak * RvChartSize.DETAIL_HEADROOM, 0f, size.height - stroke)
        val right = size.width - RvChartSize.detailEnd.toPx()
        val line = polyline(values, scale, right)
        val peakY = scale.y(peak)
        val fadeEnd = peakY + (size.height - peakY) * RvChartSize.FILL_END
        drawPath(areaUnder(line, right), Brush.verticalGradient(listOf(series.area, Color.Transparent), peakY, fadeEnd))
        dottedAcross(colors.reference, peakY)
        drawPath(line, series.line, style = lineStroke(stroke))
        EdgePill(colors.pill, colors.pillEdge).draw(this, measurer.measure(label(peak), pillStyle), peakY, end = true)
        // The low and the start sit at the bottom, where bursty traffic's line runs, so it shows through their pills.
        val overLine = EdgePill(colors.pill.copy(alpha = colors.pill.alpha * RvAlpha.PILL_OVER_LINE), colors.pillEdge)
        overLine.draw(this, measurer.measure(label(values.min()), pillStyle), scale.y(values.min()), end = true)
        overLine.draw(this, measurer.measure(label(values.first()), pillStyle), scale.y(values.first()), end = false)
        val live = Offset(right, scale.y(values.last()))
        drawCircle(series.line.copy(alpha = RvAlpha.HALO), RvChartSize.liveHalo.toPx() / 2, live)
        drawCircle(series.line, RvChartSize.liveDot.toPx() / 2, live)
        scrub?.takeIf { it in values.indices }?.let { i -> scrubMarker(Offset(right * i / (values.size - 1), scale.y(values[i])), series.line, colors.scrub, colors.page) }
    }
}

/** Press and drag: [onScrub] gets the second under the finger as it moves, and null when it lifts. */
@Composable
private fun Modifier.scrubbing(count: Int, onScrub: (Int?) -> Unit): Modifier {
    val report by rememberUpdatedState(onScrub)
    if (count < 2) return this
    return pointerInput(count) {
        val right = size.width - RvChartSize.detailEnd.toPx()
        awaitEachGesture {
            val down = awaitFirstDown()
            down.consume()
            report(sampleAt(down.position.x, right, count))
            try {
                while (true) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    change.consume()
                    report(sampleAt(change.position.x, right, count))
                }
            } finally {
                report(null)
            }
        }
    }
}

/** The sample nearest [x] when [count] samples span [0, right]. */
private fun sampleAt(x: Float, right: Float, count: Int): Int = (x / right * (count - 1)).roundToInt().coerceIn(0, count - 1)

/** Hairlines in [color]: a column every [RvChartSize.gridColumn] from the start, and the edges of four rows. */
private fun DrawScope.hairlineGrid(color: Color) {
    val hairline = 1f
    val column = RvChartSize.gridColumn.toPx()
    var x = column
    while (x < size.width) {
        drawLine(color, Offset(x, 0f), Offset(x, size.height), hairline)
        x += column
    }
    for (k in 0..RvChartSize.GRID_ROWS) {
        val y = (size.height - hairline) * k / RvChartSize.GRID_ROWS + hairline / 2
        drawLine(color, Offset(0f, y), Offset(size.width, y), hairline)
    }
}

/** The scrubbed second: a line down the chart in [rule], and a dot in [color] on the curve, ringed in [ring]. */
private fun DrawScope.scrubMarker(at: Offset, color: Color, rule: Color, ring: Color) {
    drawLine(rule, Offset(at.x, 0f), Offset(at.x, size.height), RvChartSize.scrubLine.toPx())
    val dot = RvChartSize.scrubDot.toPx() / 2
    drawCircle(ring, dot + RvChartSize.scrubRing.toPx(), at)
    drawCircle(color, dot, at)
}

/**
 * [values] spread over [0, right], joined by straight segments: at one sample a second the line stays dense and jagged,
 * as Revolut's are, and a spike keeps its sharp tip.
 */
private fun polyline(values: List<Float>, scale: Scale, right: Float): Path {
    val step = right / (values.size - 1)
    return Path().apply {
        moveTo(0f, scale.y(values[0]))
        for (i in 1 until values.size) lineTo(i * step, scale.y(values[i]))
    }
}

/** The area under [line] from its start to [right], down to the canvas's bottom. */
private fun DrawScope.areaUnder(line: Path, right: Float) = Path().apply {
    addPath(line)
    lineTo(right, size.height)
    lineTo(0f, size.height)
    close()
}

private fun lineStroke(width: Float) = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round)

/** Round dots, as Revolut draws its reference lines. */
private fun DrawScope.dots() = PathEffect.dashPathEffect(floatArrayOf(DOT_LENGTH, RvChartSize.dottedPitch.toPx()))

private fun DrawScope.dottedAcross(color: Color, y: Float) =
    drawLine(color, Offset(0f, y), Offset(size.width, y), RvChartSize.dotted.toPx(), StrokeCap.Round, dots())

/** A value pill in [fill], with a hairline [edge] in light: only ever at the chart's right edge, or its left for the start. */
private class EdgePill(val fill: Color, val edge: Color?) {
    /** [text] in the pill, centred on [y] inside the chart, [RvChartSize.pillInset] in from the [end] or start edge. */
    fun draw(scope: DrawScope, text: TextLayoutResult, y: Float, end: Boolean) = with(scope) {
        val padH = RvChartSize.pillPadH.toPx()
        val padV = RvChartSize.pillPadV.toPx()
        val pill = Size(text.size.width + 2 * padH, text.size.height + 2 * padV)
        val inset = RvChartSize.pillInset.toPx()
        val topLeft = Offset(if (end) size.width - inset - pill.width else inset, (y - pill.height / 2).coerceIn(0f, size.height - pill.height))
        val radius = CornerRadius(RvChartSize.pillRadius.toPx())
        drawRoundRect(fill, topLeft, pill, radius)
        edge?.let { drawRoundRect(it, topLeft, pill, radius, Stroke(1f)) }
        drawText(text, topLeft = topLeft + Offset(padH, padV))
    }
}

/** Ghost bar heights as shares of the chart's height, repeated across the tile. */
private val GHOST_HEIGHTS = floatArrayOf(0.42f, 0.64f, 0.5f, 0.8f, 0.58f, 0.36f, 0.7f, 0.9f, 0.54f, 0.68f, 0.46f, 0.76f)

/** A dash this short with round caps draws as a dot. */
private const val DOT_LENGTH = 0.01f
