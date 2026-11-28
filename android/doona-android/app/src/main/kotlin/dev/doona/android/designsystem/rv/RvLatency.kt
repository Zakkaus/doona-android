package dev.doona.android.designsystem.rv

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import dev.doona.android.R
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.max

// A node's latency as a row shows it: the figure, 逾時 or a dash, over its change since the test before.

/**
 * A latency's change since the test before, as Revolut writes a delta (measured §7): a filled 8 dp triangle pointing its
 * way, then the amount, in one hue: the good hue when it fell, the slow hue when it rose. No change is a dash.
 */
@Composable
internal fun RvDelta(changeMs: Int?) {
    if (changeMs == null) return RvText(RV_NO_VALUE, Rv.type.rowSub, color = Rv.colors.textSecondary) // honk-gap:node-history
    // No change is not a measurement, so it shows a dash and no triangle.
    if (changeMs == 0) {
        val none = stringResource(R.string.rv_a11y_no_change)
        return RvText(RV_NO_VALUE, Rv.type.rowSub, Modifier.semantics { contentDescription = none }, color = Rv.colors.textSecondary)
    }
    val color = if (changeMs < 0) Rv.colors.good else Rv.colors.slow
    // The triangle and the colour carry the direction for the eye; a screen reader is told it in words (a latency that fell is faster).
    val spoken = stringResource(if (changeMs < 0) R.string.rv_a11y_faster else R.string.rv_a11y_slower, stringResource(R.string.latency_ms, abs(changeMs)))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RvHomeSize.deltaGap)) {
        RvDeltaTriangle(up = changeMs > 0, color)
        // The words ride on the amount's own node, so the row's measured parts stay what they were.
        RvText(stringResource(R.string.latency_ms, abs(changeMs)), Rv.type.rowSub, Modifier.semantics { contentDescription = spoken }, color = color)
    }
}

/** A delta's filled 8 dp triangle (measured §7), pointing up when [up], in [color]: [RvDelta]'s, and the stats cards'. */
@Composable
internal fun RvDeltaTriangle(up: Boolean, color: Color) {
    Canvas(Modifier.size(RvHomeSize.delta)) {
        val h = size.width * TRIANGLE_HEIGHT
        val top = (size.height - h) / 2
        val path = Path().apply {
            if (up) {
                moveTo(size.width / 2, top)
                lineTo(size.width, top + h)
                lineTo(0f, top + h)
            } else {
                moveTo(0f, top)
                lineTo(size.width, top)
                lineTo(size.width / 2, top + h)
            }
            close()
        }
        drawPath(path, color)
    }
}

/** Whether a latency test of every node is running: every [RvLatencyChange] shows [RvBusy] until its result arrives. */
internal val LocalRvTesting = compositionLocalOf { false }

/** The nodes a test of their own is running for (a row menu's 測試延遲): only their rows show [RvBusy]. */
internal val LocalRvTestingNodes = compositionLocalOf { emptySet<String>() }

/**
 * A node's latency as a trailing value (19 sp semibold, as [RvLatencyText] writes it) over its change since the test
 * before ([RvDelta], 17 sp). While a test runs ([LocalRvTesting], or one of [node]'s own in [LocalRvTestingNodes]) a busy
 * bar stands in for both, at the end of the room they take: the row's text keeps its width and its lines through the
 * test. When the test ends the result fades in [order] × [RvMotion.STAGGER_MS] after the list's first, so the results run
 * down the list, and any later change cross-fades.
 */
@Composable
internal fun RvLatencyChange(latencyMs: Int?, changeMs: Int?, timedOut: Boolean = false, order: Int = 0, node: String? = null) {
    val motion = Rv.motion
    val testing = LocalRvTesting.current || node in LocalRvTestingNodes.current
    var waiting by remember { mutableStateOf(testing) }
    // Only while what it shows lags the test: a row at rest launches nothing, and a list has one per row.
    if (waiting != testing) {
        LaunchedEffect(testing) {
            if (!testing && !motion.instant) delay(order.toLong() * RvMotion.STAGGER_MS)
            waiting = testing
        }
    }
    Crossfade(if (waiting) null else Triple(latencyMs, changeMs, timedOut), animationSpec = motion.tween(RvMotion.FADE_MS), label = "latency") { shown ->
        if (shown == null) {
            InRoomOf({ LatencyOverChange(latencyMs, changeMs, timedOut) }) { RvBusy(Modifier.width(RvSize.busy).testTag(RvPart.BUSY)) }
        } else {
            LatencyOverChange(shown.first, shown.second, shown.third)
        }
    }
}

/**
 * [busy] at the end of the room [room] would take, [room] itself neither drawn nor read out: a row's text keeps its
 * width and its lines while the bar stands in. The bar is centred in the room; where the row's end went under its text
 * ([LocalRvEndSide]) it is centred on the room's first line ([RvEndFirstLine]), where the figure stands at rest. The room
 * is an even number of pixels tall, so the bar centred in it lands on the pixel row it held when it was centred on the
 * row alone (both halves round alike). Where the bar starts is [RvEndInk].
 */
@Composable
private fun InRoomOf(room: @Composable () -> Unit, busy: @Composable () -> Unit) {
    val side = LocalRvEndSide.current
    Layout({
        Box(Modifier.clearAndSetSemantics {}) { room() }
        busy()
    }) { (roomPart, busyPart), constraints ->
        val held = roomPart.measure(constraints)
        val bar = busyPart.measure(constraints)
        val width = max(held.width, bar.width)
        val height = max(held.height, bar.height).let { it + it % 2 }
        val line = held[RvEndFirstLine].takeUnless { it == AlignmentLine.Unspecified } ?: height
        layout(width, height, mapOf(RvEndFirstLine to line, RvEndInk to width - bar.width)) {
            val middle = if (side?.under == true) line else height
            bar.place(width - bar.width, (middle - bar.height) / 2)
        }
    }
}

/** [RvLatencyText] over the change ([RvDelta]) when there is one, both at the end; the figure is the first line. */
@Composable
private fun LatencyOverChange(latencyMs: Int?, changeMs: Int?, timedOut: Boolean) {
    Column(horizontalAlignment = Alignment.End) {
        Box(Modifier.rvEndFirstLine()) { RvLatencyText(latencyMs, timedOut) }
        RvDelta(changeMs)
    }
}

/**
 * A node's last test as a row's trailing value: its latency; 逾時 in the slow colour of a latency that went up, when the
 * test timed out; a dash when there is nothing to show (never tested, or no node).
 */
@Composable
internal fun RvLatencyText(latencyMs: Int?, timedOut: Boolean) {
    when {
        latencyMs != null -> RvText(stringResource(R.string.latency_ms, latencyMs), Rv.type.rowValue, align = TextAlign.End)

        timedOut -> RvText(stringResource(R.string.rv_nodes_timed_out), Rv.type.rowValue, color = Rv.colors.slow, align = TextAlign.End)

        else -> {
            val untested = stringResource(R.string.latency_untested)
            RvText(RV_NO_VALUE, Rv.type.rowValue, Modifier.semantics { contentDescription = untested }, align = TextAlign.End)
        }
    }
}

/** An equilateral triangle is this share of its width high. */
private const val TRIANGLE_HEIGHT = 0.866f

/** A latency's colour: the fixed good, fair and slow hues. */
@Composable
@ReadOnlyComposable
internal fun latencyColor(ms: Int?): Color = Rv.colors.quality(latencyTone(ms))

/** [ms] as the app writes a latency, or a dash for a test that timed out. */
@Composable
internal fun latencyText(ms: Int?): String = ms?.let { stringResource(R.string.latency_ms, it) } ?: RV_NO_VALUE
