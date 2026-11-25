package dev.doona.android.prototype.rv

import androidx.compose.foundation.layout.Column
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

/** [RvItems]: rows first shown stand at rest; a removed row leaves where it stood; a new row grows in under the others. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvItemsTest {
    @get:Rule
    val compose = createComposeRule()

    private var items by mutableStateOf(listOf("甲", "乙", "丙"))

    private fun show() = compose.setContent {
        RvTheme(seedColorScheme(Seed.Cyan.argb, false), false) {
            Column(Modifier.testTag(LIST)) { RvItems(items, { it }) { Text(it) } }
        }
    }

    @Test
    fun aRemovedRowLeavesWhereItStood() {
        show()
        compose.mainClock.autoAdvance = false
        val gap = compose.onNodeWithText("乙").getUnclippedBoundsInRoot().top
        change(listOf("甲", "丙"))
        compose.onNodeWithText("乙").assertExists()
        assertTrue("丙 has not jumped into 乙's place", compose.onNodeWithText("丙").getUnclippedBoundsInRoot().top > gap)
        compose.mainClock.advanceTimeBy(RvMotion.ITEM_MS + FRAME_MS)
        assertEquals(0, compose.onAllNodesWithText("乙").fetchSemanticsNodes().size)
    }

    @Test
    fun aNewRowGrowsInWhileTheFirstStandAtRest() {
        show()
        compose.mainClock.autoAdvance = false
        val first = compose.onNodeWithText("丙").getUnclippedBoundsInRoot().bottom
        val before = compose.onNodeWithTag(LIST).getUnclippedBoundsInRoot().height
        change(listOf("甲", "乙", "丙", "丁"))
        val growing = compose.onNodeWithTag(LIST).getUnclippedBoundsInRoot().height
        compose.mainClock.advanceTimeBy(RvMotion.ITEM_MS + FRAME_MS)
        val settled = compose.onNodeWithTag(LIST).getUnclippedBoundsInRoot().height
        assertEquals(first, compose.onNodeWithText("丁").getUnclippedBoundsInRoot().top)
        assertTrue("the list jumped to its new height", growing < settled)
        assertTrue("丁 took no room", before < settled)
    }

    /** Hands [next] to the composition (the clock held) and runs one frame of the motion it starts. */
    private fun change(next: List<String>) {
        items = next
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(FRAME_MS)
    }

    private companion object {
        const val FRAME_MS = 32L
        const val LIST = "list"
    }
}
