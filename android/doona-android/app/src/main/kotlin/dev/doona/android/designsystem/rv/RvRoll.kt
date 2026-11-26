package dev.doona.android.designsystem.rv

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import kotlin.math.abs

/**
 * A live number (a rate, a latency) for [value], drawn by [content] from [shown] (its words, such as a number and its
 * unit): when [shown] changes, the characters that changed roll, all of them at once, the old ones leaving and the new
 * ones arriving [RvMotion.ROLL_SHARE] of the text's height away and fading, upward when [value] rose and downward when
 * it fell, in [RvMotion.ROLL_MS]. A character that kept its place (the same one at the same spot, as tabular figures
 * keep it) stays still. At rest [content] is drawn as it is, at [alignment]; while not [live] (a scrubbed point
 * following the finger) a change shows at once. The text inside [content] rolls through [rvRollText].
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
    val drive = remember { RvRollDrive(shown, value) }
    // The change is marked in the composition that brings it, so the frame that first draws the new text draws the roll's start.
    if (drive.shown != shown) drive.change(shown, value, roll = live && !motion.instant)
    LaunchedEffect(shown) {
        if (!drive.pending) return@LaunchedEffect drive.progress.snapTo(1f)
        drive.progress.snapTo(0f)
        drive.pending = false
        drive.progress.animateTo(1f, motion.tween(RvMotion.ROLL_MS, LinearEasing))
    }
    Box(modifier, contentAlignment = alignment) {
        CompositionLocalProvider(LocalRvRoll provides drive) { content(shown) }
    }
}

/** The roll a [RvRoll] runs: how far it has gone (0 to 1, linear in time) and which way. */
internal class RvRollDrive(var shown: Any?, private var value: Double) {
    val progress = Animatable(1f)
    var up = true
        private set
    var pending = false

    /** Counts the changes, so a text knows which layout came before the change it rolls for. */
    var changes = 0
        private set

    /** Read in the draw: it reads [progress] first, so the draw follows every frame of the roll. */
    val at: Float get() = progress.value.let { if (pending) 0f else it }

    fun change(next: Any?, nextValue: Double, roll: Boolean) {
        up = nextValue >= value
        shown = next
        value = nextValue
        pending = roll
        changes++
    }
}

internal val LocalRvRoll = staticCompositionLocalOf<RvRollDrive?> { null }

/**
 * The roll of a text inside a [RvRoll], for the text primitive to apply: [modifier] last on its text, [laid] from its
 * `onTextLayout`. Outside a [RvRoll] it does nothing.
 */
@Composable
internal fun rvRollText(): RvRollText {
    val drive = LocalRvRoll.current
    return remember(drive) { RvRollText(drive) }
}

internal class RvRollText(private val drive: RvRollDrive?) {
    private var from: TextLayoutResult? = null
    private var now: TextLayoutResult? = null
    private var seen = 0

    val modifier: Modifier = if (drive == null) Modifier else Modifier.drawWithContent { draw(drive) }

    fun laid(layout: TextLayoutResult) {
        // The layout before the change rolls out; a second layout of the new text (a word cut) leaves it.
        if (drive != null && drive.changes != seen) {
            seen = drive.changes
            from = now
        }
        now = layout
    }

    private fun ContentDrawScope.draw(drive: RvRollDrive) {
        val at = drive.at
        val now = now
        val from = from
        if (at >= 1f || now == null || from == null) return drawContent()
        val moved = rvRollMoved(from, now)
        if (moved.isEmpty) return drawContent()
        val travel = (if (drive.up) 1 else -1) * size.height * RvMotion.ROLL_SHARE
        val eased = RvMotion.Emphasized.transform(at)
        val into = columns(now, moved.into, size.height)
        clipPath(into, ClipOp.Difference) { this@draw.drawContent() }
        translate(top = travel * (1 - eased)) {
            clipPath(into) {
                drawContext.canvas.saveLayer(Rect(-size.width, -size.height, size.width * 2, size.height * 2), Paint().apply { alpha = eased })
                this@draw.drawContent()
                drawContext.canvas.restore()
            }
        }
        translate(top = -travel * eased) {
            clipPath(columns(from, moved.out, size.height)) { drawText(from, alpha = 1 - RvMotion.Accelerate.transform(at)) }
        }
    }

    /** The columns of [chars] in [layout], each as wide as its character and tall enough for its glyph wherever it moves. */
    private fun columns(layout: TextLayoutResult, chars: List<Int>, height: Float) = Path().apply {
        chars.forEach { addRect(layout.getBoundingBox(it).let { box -> Rect(box.left, -height, box.right, height * 2) }) }
    }
}

/** Which characters roll: [into] in the new text, [out] in the old one. */
internal class RvRollMoves(val into: List<Int>, val out: List<Int>) {
    val isEmpty: Boolean get() = into.isEmpty() && out.isEmpty()
}

/**
 * The characters that changed from [from] to [now], the two texts set against each other from their ends (a number's
 * last digit against its last digit, the unit against the unit): a character stays when the other text has the same
 * one at the same place, within half a pixel; any other rolls.
 */
internal fun rvRollMoved(from: TextLayoutResult, now: TextLayoutResult): RvRollMoves {
    val old = from.layoutInput.text.text
    val new = now.layoutInput.text.text
    val into = new.indices.filterNot { rvRollStays(from, old.length - new.length + it, now, it) }
    val out = old.indices.filterNot { rvRollStays(from, it, now, new.length - old.length + it) }
    return RvRollMoves(into, out)
}

private fun rvRollStays(from: TextLayoutResult, i: Int, now: TextLayoutResult, j: Int): Boolean {
    val old = from.layoutInput.text.text
    val new = now.layoutInput.text.text
    if (i !in old.indices || j !in new.indices || old[i] != new[j]) return false
    val a = from.getBoundingBox(i)
    val b = now.getBoundingBox(j)
    return abs(a.left - b.left) < RvMotion.REST_PX && abs(a.right - b.right) < RvMotion.REST_PX
}
