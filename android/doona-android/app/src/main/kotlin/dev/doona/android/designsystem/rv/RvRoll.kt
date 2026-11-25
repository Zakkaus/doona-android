package dev.doona.android.designsystem.rv

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlin.math.roundToInt

/**
 * A live number (a rate, a latency) for [value], drawn by [content] from [shown] (its words, such as a number and its
 * unit): when [shown] changes it rolls, the old one leaving and the new one arriving [RvMotion.ROLL_SHARE] of its height
 * away and fading, upward when [value] rose and downward when it fell, in [RvMotion.ROLL_MS]. A tick that leaves
 * [shown] as it was moves nothing. At rest [content] is the one thing drawn, at [alignment]; while not [live] (a scrubbed
 * point following the finger) a change shows at once.
 */
@Composable
internal fun <T> RvRoll(
    shown: T,
    value: Double,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.TopStart,
    live: Boolean = true,
    content: @Composable (T) -> Unit,
) {
    val motion = Rv.motion
    AnimatedContent(
        RvRolled(shown, value),
        modifier,
        transitionSpec = { if (live && !motion.instant) rvRollMove(motion, up = targetState.value >= initialState.value) else rvNoMove },
        contentAlignment = alignment,
        label = "roll",
        contentKey = { it.shown },
    ) { content(it.shown) }
}

private val rvNoMove = ContentTransform(EnterTransition.None, ExitTransition.None, sizeTransform = null)

/** The roll: the new text arriving from below when [up], from above when not, and the old one leaving the other way. */
private fun rvRollMove(motion: RvMotionScale, up: Boolean): ContentTransform {
    val ms = RvMotion.ROLL_MS
    val sign = if (up) 1 else -1
    val enter = slideInVertically(motion.tween(ms, RvMotion.Emphasized)) { (sign * it * RvMotion.ROLL_SHARE).roundToInt() } +
        fadeIn(motion.tween(ms, RvMotion.Emphasized))
    val exit = slideOutVertically(motion.tween(ms, RvMotion.Emphasized)) { (-sign * it * RvMotion.ROLL_SHARE).roundToInt() } +
        fadeOut(motion.tween(ms, RvMotion.Accelerate))
    return ContentTransform(enter, exit, sizeTransform = null)
}

@Immutable
private data class RvRolled<T>(val shown: T, val value: Double)
