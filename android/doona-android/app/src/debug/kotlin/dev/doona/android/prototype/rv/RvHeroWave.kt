package dev.doona.android.prototype.rv

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onPlaced
import dev.doona.android.engine.Status
import dev.doona.android.feature.activity.ActivityUiState
import dev.doona.android.prototype.rv.data.RvRouteMode

/**
 * V2 · Live waveform: the last minute fills the hero from the top bar's controls to the actions, download rising above
 * the figure and upload mirrored under it, each on its own scale so both keep their shape. The centre line runs behind
 * the figure, so the curves part round it and never cross its text. A finger on it scrubs as on 流量詳情: a line in
 * download's colour and a dot on each curve, the figure shows that second's time and rates, and lifting the finger
 * brings back the live figure. Disconnected, both halves are flat lines.
 */
@Composable
internal fun RvWaveHero(state: ActivityUiState, mode: RvRouteMode, insets: WindowInsets, actions: @Composable () -> Unit) {
    var scrub by remember { mutableStateOf<Int?>(null) }
    var figure by remember { mutableStateOf<Rect?>(null) }
    val live = state.status is Status.Running && state.downHistory.size == state.upHistory.size
    val down = if (live) state.downHistory else emptyList()
    val up = if (live) state.upHistory else emptyList()
    RvHeroColumn(insets) {
        Spacer(Modifier.height(rvBarHeight()))
        Box(Modifier.fillMaxWidth().height(RvHeroSize.wave).rvScrubbing(down.size) { scrub = it }, contentAlignment = Alignment.Center) {
            Waveform(down, up, scrub, figure, Modifier.matchParentSize())
            RvHeroFigure(state, mode, Modifier.onPlaced { figure = it.boundsInParent() }, scrub)
        }
        actions()
    }
}

@Composable
private fun Waveform(down: List<Float>, up: List<Float>, scrub: Int?, figure: Rect?, modifier: Modifier) {
    val colors = Rv.colors
    Canvas(modifier) {
        val gap = RvHeroSize.waveGap.toPx()
        val inset = RvHeroSize.waveInset.toPx()
        // Download rises from just above the figure, upload hangs from just under it.
        val top = (figure?.top ?: (size.height / 2)) - gap
        val bottom = (figure?.bottom ?: (size.height / 2)) + gap
        val right = size.width - RvChartSize.detailEnd.toPx()
        if (down.size < 2) {
            for (y in listOf(top, bottom)) drawLine(colors.control, Offset(0f, y), Offset(size.width, y), RvChartSize.detailStroke.toPx(), StrokeCap.Round)
            return@Canvas
        }
        for (y in listOf(top, bottom)) drawLine(colors.grid, Offset(0f, y), Offset(size.width, y), 1f)
        val downY = wave(down, top, inset - top, right, colors.down)
        val upY = wave(up, bottom, size.height - inset - bottom, right, colors.up)
        val i = scrub?.takeIf { it in down.indices } ?: return@Canvas
        val x = right * i / (down.size - 1)
        val line = RvChartSize.scrubLine.toPx()
        drawLine(colors.down.line, Offset(x, 0f), Offset(x, top), line)
        drawLine(colors.down.line, Offset(x, bottom), Offset(x, size.height), line)
        downY(i)?.let { rvScrubDot(Offset(x, it), colors.down.line, colors.page) }
        upY(i)?.let { rvScrubDot(Offset(x, it), colors.up.line, colors.page) }
    }
}

/**
 * One direction's half: [values] over [0, right], from [mid] out by up to [reach] (negative is up) at its own peak ×
 * [RvChartSize.DETAIL_HEADROOM], as an area that fades in from the centre line toward the curve, with the curve on its
 * edge and a halo on the live point. A missing sample (NaN) breaks both. Returns where a sample sits, null for a gap.
 */
private fun DrawScope.wave(values: List<Float>, mid: Float, reach: Float, right: Float, series: RvSeries): (Int) -> Float? {
    val peak = values.filterNot { it.isNaN() }.maxOrNull()?.takeIf { it > 0f } ?: return { null }
    val high = peak * RvChartSize.DETAIL_HEADROOM
    val at = { i: Int -> mid + reach * values[i] / high }
    val step = right / (values.size - 1)
    val edge = Path()
    val area = Path()
    for (run in runs(values)) {
        edge.moveTo(run.first * step, at(run.first))
        area.moveTo(run.first * step, mid)
        for (i in run) {
            edge.lineTo(i * step, at(i))
            area.lineTo(i * step, at(i))
        }
        area.lineTo(run.last * step, mid)
        area.close()
    }
    drawPath(area, Brush.verticalGradient(listOf(Color.Transparent, series.band), mid, mid + reach))
    drawPath(edge, series.line, style = Stroke(RvChartSize.detailStroke.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    if (!values.last().isNaN()) {
        drawCircle(series.line.copy(alpha = RvAlpha.HALO), RvChartSize.liveHalo.toPx() / 2, Offset(right, at(values.lastIndex)))
        drawCircle(series.line, RvChartSize.liveDot.toPx() / 2, Offset(right, at(values.lastIndex)))
    }
    return { i -> at(i).takeUnless { values[i].isNaN() } }
}

/** The unbroken runs of [values]: the index ranges between missing samples (NaN). */
private fun runs(values: List<Float>): List<IntRange> = buildList {
    var start = -1
    for (i in 0..values.size) {
        val known = i < values.size && !values[i].isNaN()
        if (known && start < 0) start = i
        if (!known && start >= 0) {
            add(start until i)
            start = -1
        }
    }
}
