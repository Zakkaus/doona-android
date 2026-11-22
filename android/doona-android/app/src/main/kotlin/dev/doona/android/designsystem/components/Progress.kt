package dev.doona.android.designsystem.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import dev.doona.android.designsystem.theme.DoonaMotion
import dev.doona.android.designsystem.theme.DoonaTheme

/** Where a [DoonaProgress] sits: [Inline] is the size of an inline icon, on a line of text; [Small] is the touch icon's. */
enum class DoonaProgressSize { Inline, Small }

/**
 * An indeterminate progress spinner in the accent colour, for work with no known end: a list that loads, a request on
 * its way. A screen reader hears it as in progress, in the user's language, which Android says for an indeterminate
 * range, so it carries no description of its own. A button shows its own state: use [DoonaButton]'s `busy` there.
 */
@Composable
fun DoonaProgress(modifier: Modifier = Modifier, size: DoonaProgressSize = DoonaProgressSize.Small) {
    val box = if (size == DoonaProgressSize.Inline) DoonaTheme.size.iconInline else DoonaTheme.size.icon
    Spinner(
        DoonaTheme.colors.accent,
        box,
        modifier.semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate },
    )
}

/**
 * The spinner in any [color] and [box] size, which a button's busy state uses in its label's colour: a round-ended arc
 * of three quarters of a turn, drawn with the spinner stroke and turning once in [DoonaMotion.SPINNER_TURN_MS]. A still frame, such as a
 * screenshot, shows that arc where Material's indicator would show a dot.
 */
@Composable
internal fun Spinner(color: Color, box: Dp, modifier: Modifier = Modifier) {
    val stroke = DoonaTheme.size.spinnerStroke
    val turn by rememberInfiniteTransition(label = "spinner").animateFloat(0f, FULL_TURN, infiniteRepeatable(tween(DoonaMotion.SPINNER_TURN_MS, easing = LinearEasing), RepeatMode.Restart), label = "turn")
    Canvas(modifier.size(box)) {
        val width = stroke.toPx()
        drawArc(
            color,
            startAngle = turn - QUARTER_TURN,
            sweepAngle = ARC,
            useCenter = false,
            topLeft = Offset(width / 2, width / 2),
            size = Size(this.size.width - width, this.size.height - width),
            style = Stroke(width, cap = StrokeCap.Round),
        )
    }
}

private const val FULL_TURN = 360f
private const val QUARTER_TURN = 90f
private const val ARC = 270f
