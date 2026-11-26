package dev.doona.android.prototype.rv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import dev.doona.android.designsystem.rv.Rv
import dev.doona.android.designsystem.rv.RvMotion
import dev.doona.android.designsystem.rv.RvRoll
import dev.doona.android.designsystem.rv.RvText
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.designsystem.rv.RvTypeScale
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * [RvRoll]: only the digits that changed roll, all of them the same way (up when the number rose, down when it fell),
 * while the digits and the unit that kept their place stay still; it lands on the plain text; a scrub and animator
 * scale 0 move nothing.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvRollTest {
    @get:Rule
    val compose = createComposeRule()

    private var value by mutableStateOf(1015.0)
    private var live by mutableStateOf(true)

    private fun show() = compose.setContent {
        RvTheme(seedColorScheme(Seed.Cyan.argb, false), false) {
            val style = Rv.type.sub.copy(fontFeatureSettings = RvTypeScale.TABULAR)
            Box(Modifier.background(Color.White).padding(PAD)) {
                RvRoll("${value.toInt()} MB/s", value, live = live) { RvText(it, style, color = Color.Black) }
            }
        }
    }

    @Test
    fun aRiseRollsTheChangedDigitsUpAndLeavesTheRest() = rolls(to = 1027.0, up = true)

    @Test
    fun aFallRollsTheChangedDigitsDownAndLeavesTheRest() = rolls(to = 1003.0, up = false)

    @Test
    fun aScrubbedNumberShowsAtOnce() {
        live = false
        atOnce()
    }

    @Test
    fun atAnimatorScaleZeroANumberShowsAtOnce() {
        rvAnimatorScale(0f)
        atOnce()
    }

    /** From 1015 to [to] (two digits change): the changed columns roll [up] or down, the rest of the line holds still. */
    private fun rolls(to: Double, up: Boolean) {
        show()
        compose.mainClock.autoAdvance = false
        val rest = shot()
        val target = settled(to)
        // Back to the start at once, then the live change.
        live = false
        change(1015.0)
        live = true
        change(to)
        val mid = shot()
        val (x0, x1) = changedColumns(rest, target)
        val still = (0 until rest.width).filter { it < x0 - EDGE || it > x1 + EDGE }
        assertTrue("the kept digits and the unit stay still", still.all { x -> (0 until rest.height).all { y -> rest[x, y] == mid[x, y] } })
        // Both changed digits (the tens and the units) move the same way.
        val half = (x0 + x1) / 2
        listOf(x0..half, half + 1..x1).forEach { cols ->
            val (top, bottom) = ink(rest, cols)
            val (midTop, midBottom) = ink(mid, cols)
            if (up) {
                assertTrue("rises: ink at $midTop, above the rest's $top", midTop < top - 2)
            } else {
                assertTrue("falls: ink to $midBottom, below the rest's $bottom", midBottom > bottom + 2)
            }
        }
        compose.mainClock.advanceTimeBy(RvMotion.ROLL_MS + FRAME_MS * 2)
        assertTrue("lands on the plain text", same(target, shot()))
    }

    private fun atOnce() {
        show()
        compose.mainClock.autoAdvance = false
        val target = settled(1027.0)
        value = 1015.0
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(RvMotion.ROLL_MS + FRAME_MS * 2)
        change(1027.0)
        assertTrue("shows at once", same(target, shot()))
    }

    /** The plain text of [next], reached without a roll. */
    private fun settled(next: Double): PixelMap {
        val was = live
        live = false
        value = next
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(RvMotion.ROLL_MS + FRAME_MS * 2)
        return shot().also { live = was }
    }

    /** Hands [next] to the composition (the clock held) and runs two frames of the roll it starts. */
    private fun change(next: Double) {
        value = next
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(FRAME_MS * 2)
    }

    private fun shot(): PixelMap = compose.onRoot().captureToImage().toPixelMap()

    private fun same(a: PixelMap, b: PixelMap) = (0 until a.width).all { x -> (0 until a.height).all { y -> a[x, y] == b[x, y] } }

    /** The first and last columns where [a] and [b] differ. */
    private fun changedColumns(a: PixelMap, b: PixelMap): Pair<Int, Int> {
        val cols = (0 until a.width).filter { x -> (0 until a.height).any { y -> a[x, y] != b[x, y] } }
        assertTrue("the two numbers differ", cols.isNotEmpty())
        return cols.first() to cols.last()
    }

    /** The first and last rows holding ink within [cols]. */
    private fun ink(map: PixelMap, cols: IntRange): Pair<Int, Int> {
        val rows = (0 until map.height).filter { y -> cols.any { x -> abs(map[x, y].red - 1f) > INK } }
        assertTrue("ink in $cols", rows.isNotEmpty())
        return rows.first() to rows.last()
    }

    private companion object {
        const val FRAME_MS = 32L
        const val INK = 0.15f
        const val EDGE = 3
        val PAD = 40.dp
    }
}
