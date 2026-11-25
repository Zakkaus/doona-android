package dev.doona.android.prototype.rv

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.doona.android.designsystem.rv.RvMotion
import dev.doona.android.designsystem.rv.RvRoll
import dev.doona.android.designsystem.rv.RvTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** [RvRoll]: a number that rose arrives from below, one that fell from above; a scrub and animator scale 0 move nothing. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvRollTest {
    @get:Rule
    val compose = createComposeRule()

    private var value by mutableStateOf(5.0)
    private var live = true

    private fun show() = compose.setContent {
        RvTheme(seedColorScheme(Seed.Cyan.argb, false), false) {
            Box(Modifier.padding(top = PAD)) { RvRoll("${value.toInt()} MB/s", value, live = live) { Text(it) } }
        }
    }

    @Test
    fun aNumberThatRoseArrivesFromBelow() {
        show()
        compose.mainClock.autoAdvance = false
        val rest = top("5 MB/s")
        change(7.0)
        assertTrue("7 arrives from below: ${top("7 MB/s") - rest}", top("7 MB/s") > rest)
        assertTrue("5 leaves upward", top("5 MB/s") < rest)
        settle()
        assertEquals(rest, top("7 MB/s"))
    }

    @Test
    fun aNumberThatFellArrivesFromAbove() {
        show()
        compose.mainClock.autoAdvance = false
        val rest = top("5 MB/s")
        change(3.0)
        assertTrue("3 arrives from above: ${top("3 MB/s") - rest}", top("3 MB/s") < rest)
        assertTrue("5 leaves downward", top("5 MB/s") > rest)
        settle()
        assertEquals(rest, top("3 MB/s"))
    }

    @Test
    fun aScrubbedNumberShowsAtOnce() {
        live = false
        show()
        compose.mainClock.autoAdvance = false
        val rest = top("5 MB/s")
        change(7.0)
        assertEquals(rest, top("7 MB/s"))
    }

    @Test
    fun atAnimatorScaleZeroANumberShowsAtOnce() {
        rvAnimatorScale(0f)
        show()
        compose.mainClock.autoAdvance = false
        val rest = top("5 MB/s")
        change(7.0)
        assertEquals(rest, top("7 MB/s"))
    }

    private fun top(text: String): Dp = compose.onNodeWithText(text).getUnclippedBoundsInRoot().top

    /** Hands [next] to the composition (the clock held) and runs two frames of the roll it starts. */
    private fun change(next: Double) {
        value = next
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(FRAME_MS * 2)
    }

    private fun settle() = compose.mainClock.advanceTimeBy(RvMotion.ROLL_MS + FRAME_MS * 2)

    private companion object {
        const val FRAME_MS = 32L
        val PAD = 40.dp
    }
}
