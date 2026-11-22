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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import dev.doona.android.R
import dev.doona.android.designsystem.theme.DoonaTheme

/** Where a [DoonaProgress] sits: [Inline] is the size of an inline icon, on a line of text; [Small] is the touch icon's. */
enum class DoonaProgressSize { Inline, Small }

/**
 * An indeterminate progress spinner in the accent colour, for work with no known end: a list that loads, a request on
 * its way. A screen reader hears it as in progress. A button shows its own state: use [DoonaButton]'s `busy` there.
 */
@Composable
fun DoonaProgress(modifier: Modifier = Modifier, size: DoonaProgressSize = DoonaProgressSize.Small) {
    val description = stringResource(R.string.ui_in_progress)
    val box = if (size == DoonaProgressSize.Inline) DoonaTheme.size.iconInline else DoonaTheme.size.icon
    Spinner(
        DoonaTheme.colors.accent,
        box,
        modifier.semantics {
            contentDescription = description
            progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
        },
    )
}

/**
 * The spinner in any colour and size, for a button's busy state, where the label colour of the button is the spinner's: a stroke
 * 2 dp wide, round at both ends, an arc of three quarters turning once a second. Still, as a screenshot shows it, it is
 * that arc and not the speck a material indicator starts from.
 */
@Composable
internal fun Spinner(color: Color, box: Dp, modifier: Modifier = Modifier) {
    val stroke = DoonaTheme.size.borderStrong
    val turn by rememberInfiniteTransition(label = "spinner").animateFloat(0f, FULL_TURN, infiniteRepeatable(tween(TURN_MS, easing = LinearEasing), RepeatMode.Restart), label = "turn")
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
private const val TURN_MS = 1000
