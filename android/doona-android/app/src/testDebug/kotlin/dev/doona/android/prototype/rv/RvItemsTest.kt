package dev.doona.android.prototype.rv

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import dev.doona.android.designsystem.rv.RvItems
import dev.doona.android.designsystem.rv.RvMotion
import dev.doona.android.designsystem.rv.RvTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * [RvItems]: rows first shown stand at rest; a removed row leaves where it stood and takes its gap with it; a new row
 * grows in, its gap with it, so the rows below never jump; at animator scale 0 a change lands at once.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvItemsTest {
    @get:Rule
    val compose = createComposeRule()

    private var items by mutableStateOf(listOf("甲", "乙", "丙"))

    private fun show() = compose.setContent {
        RvTheme(seedColorScheme(Seed.Cyan.argb, false), false) {
            Box(Modifier.testTag(LIST)) { RvItems(items, { it }, GAP) { Text(it, Modifier.height(ROW)) } }
        }
    }

    @Test
    fun aRemovedRowLeavesWhereItStoodAndTakesItsGap() {
        show()
        compose.mainClock.autoAdvance = false
        val gone = top("乙")
        change(listOf("甲", "丙"))
        compose.onNodeWithText("乙").assertExists()
        assertTrue("丙 has not jumped into 乙's place", top("丙") > gone)
        compose.mainClock.advanceTimeBy(RvMotion.ITEM_MS + FRAME_MS * 2)
        assertEquals("乙 left the list", 0, compose.onAllNodesWithText("乙").fetchSemanticsNodes().size)
        assertEquals("one gap between the two rows left", ROW * 2 + GAP, height())
        compose.mainClock.advanceTimeBy(RvMotion.ITEM_MS.toLong())
        assertEquals("the list stays put", ROW * 2 + GAP, height())
    }

    @Test
    fun aNewRowGrowsInWhileTheFirstStandAtRest() {
        show()
        compose.mainClock.autoAdvance = false
        val first = compose.onNodeWithText("丙").getUnclippedBoundsInRoot().bottom
        val before = height()
        change(listOf("甲", "乙", "丙", "丁"))
        val growing = height()
        compose.mainClock.advanceTimeBy(RvMotion.ITEM_MS + FRAME_MS)
        assertTrue("the list jumped to its new height", growing < height())
        assertEquals("丁 took its row and one gap", before + ROW + GAP, height())
        assertEquals(first + GAP, top("丁"))
    }

    @Test
    fun aRowInsertedInTheMiddleOpensItsGapGradually() {
        show()
        compose.mainClock.autoAdvance = false
        val rest = top("乙")
        change(listOf("甲", "丁", "乙", "丙"))
        assertTrue("乙 jumped by a gap on the first frame: ${top("乙") - rest}", top("乙") - rest < GAP)
        compose.mainClock.advanceTimeBy(RvMotion.ITEM_MS + FRAME_MS)
        assertEquals(rest + ROW + GAP, top("乙"))
    }

    @Test
    fun atAnimatorScaleZeroAChangeLandsAtOnce() {
        rvAnimatorScale(0f)
        show()
        compose.mainClock.autoAdvance = false
        change(listOf("甲", "丙"))
        compose.mainClock.advanceTimeBy(FRAME_MS * 2)
        assertEquals(0, compose.onAllNodesWithText("乙").fetchSemanticsNodes().size)
        assertEquals(ROW * 2 + GAP, height())
        change(listOf("甲", "丙", "丁"))
        compose.mainClock.advanceTimeBy(FRAME_MS * 2)
        assertEquals(ROW * 3 + GAP * 2, height())
    }

    private fun top(text: String): Dp = compose.onNodeWithText(text).getUnclippedBoundsInRoot().top

    private fun height(): Dp = compose.onNodeWithTag(LIST).getUnclippedBoundsInRoot().height

    /** Hands [next] to the composition (the clock held) and runs one frame of the motion it starts. */
    private fun change(next: List<String>) {
        items = next
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(FRAME_MS)
    }

    private companion object {
        const val FRAME_MS = 32L
        const val LIST = "list"
        val ROW = 8.dp
        val GAP = 40.dp
    }
}
