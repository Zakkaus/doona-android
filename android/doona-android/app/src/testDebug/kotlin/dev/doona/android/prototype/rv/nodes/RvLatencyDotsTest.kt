package dev.doona.android.prototype.rv.nodes

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import dev.doona.android.R
import dev.doona.android.designsystem.components.latencyTone
import dev.doona.android.designsystem.rv.Rv
import dev.doona.android.prototype.rv.rvContent
import dev.doona.android.prototype.rv.rvText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * 節點詳情's latency chart with no test and with one (owner, item 7: 「沒有資料的字不居中…只有一筆也沒居中」): the plot is
 * read from the drawn hairlines (from the left edge to where the zero line ends, the top line to the zero line), not
 * from the code's own numbers. 尚無資料's box is centred on it both ways, the middle hairline stops short of it, and
 * one test's dot is at its centre, across and, by its axis, down, whatever its latency.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvLatencyDotsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theEmptyLineSitsAtThePlotsCentre() {
        compose.rvContent(dark = false) { Clear { RvLatencyDots(emptyList(), null, emptyList(), -1, {}, Modifier.testTag(CHART)) } }
        val chart = compose.onNodeWithTag(CHART).fetchSemanticsNode().boundsInRoot
        val box = compose.onNodeWithText(rvText(R.string.rv_stats_no_data)).fetchSemanticsNode().boundsInRoot.translate(-chart.left, -chart.top)
        val plot = plot(image())
        assertEquals("the line's centre across", plot.center.x, box.center.x, PX)
        assertEquals("the line's centre down", plot.center.y, box.center.y, PX)
        // The middle hairline runs on either side but not through the text: half the gap left of the box it is the background.
        val image = image()
        val middle = plot.center.y.toInt()
        val background = image.getPixel(0, 0)
        assertNotEquals("the middle hairline is drawn", background, image.getPixel(1, middle))
        assertEquals("the middle hairline stops short of the line", background, image.getPixel((box.left - GAP_PX / 2).toInt(), middle))
    }

    @Test fun oneTestSitsAtThePlotsCentre() = oneTest(ONE)

    /** A fast node's one test too: 20 ms on a 0–100 ms axis sat a fifth of the way up. */
    @Test fun oneFastTestSitsAtThePlotsCentre() = oneTest(FAST)

    private fun oneTest(ms: Int) {
        var dot = 0
        compose.rvContent(dark = false) {
            dot = Rv.colors.quality(latencyTone(ms)).toArgb()
            // No test picked, so no marker covers the hairlines the plot is read from.
            Clear { RvLatencyDots(listOf(ms), null, listOf(TIME), -1, {}, Modifier.testTag(CHART)) }
        }
        val image = image()
        val plot = plot(image)
        val xs = (0 until image.width).filter { x -> (0 until image.height).any { image.getPixel(x, it) == dot } }
        assertTrue("the dot is drawn", xs.isNotEmpty())
        assertEquals("the dot's centre across", plot.center.x, (xs.first() + xs.last() + 1) / 2f, PX)
        // Down, its axis puts it on the middle line, not alone under the top one (92 ms on 0–100 sat 8 % under the top).
        val ys = (0 until image.height).filter { y -> (0 until image.width).any { image.getPixel(it, y) == dot } }
        assertEquals("$ms ms: the dot's centre down", plot.center.y, (ys.first() + ys.last() + 1) / 2f, DOT_PX)
    }

    /** Below the frame's status bar, which draws over the top of the content. */
    @Composable
    private fun Clear(content: @Composable () -> Unit) = Column {
        Spacer(Modifier.height(CLEAR))
        content()
    }

    private fun image(): Bitmap = compose.onNodeWithTag(CHART).captureToImage().asAndroidBitmap()

    /** The plot as drawn: from the left edge to where the zero hairline ends, from the top hairline to the zero hairline. */
    private fun plot(image: Bitmap): Rect {
        val background = image.getPixel(0, 0)
        val rows = (0 until image.height).filter { image.getPixel(0, it) != background }
        val lines = rows.fold(mutableListOf<MutableList<Int>>()) { runs, y ->
            if (runs.lastOrNull()?.last() == y - 1) runs.last().add(y) else runs.add(mutableListOf(y))
            runs
        }
        assertEquals("three hairlines: the top, the middle and zero", HAIRLINES, lines.size)
        // A 1 px line between two pixel rows covers both by half; its centre is between them.
        val centre = { run: List<Int> -> (run.first() + run.last() + 1) / 2f }
        val zero = lines.last().maxBy { y -> colourDistance(image.getPixel(0, y), background) }
        val ink = image.getPixel(0, zero)
        val right = (0 until image.width).takeWhile { image.getPixel(it, zero) == ink }.last() + 1
        return Rect(0f, centre(lines.first()), right.toFloat(), centre(lines.last()))
    }

    private fun colourDistance(a: Int, b: Int) = listOf(0, 8, 16).sumOf { abs((a shr it and 0xff) - (b shr it and 0xff)) }

    private companion object {
        const val CHART = "latency-dots"
        const val ONE = 92
        const val FAST = 20
        const val TIME = "06:30"
        const val HAIRLINES = 3
        val CLEAR = 80.dp

        /** One pixel either way, and `plotGap` (8 dp) at xhdpi. */
        const val PX = 1f
        const val GAP_PX = 16f

        /** Two pixels either way: the dot's drawn edge is matched by its exact colour. */
        const val DOT_PX = 2f
    }
}
