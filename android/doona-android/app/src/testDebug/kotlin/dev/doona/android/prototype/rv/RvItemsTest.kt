package dev.doona.android.prototype.rv

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
            Column { RvItems(items, { it }) { Text(it) } }
        }
    }

    @Test
    fun aRemovedRowLeavesWhereItStood() {
        show()
        compose.mainClock.autoAdvance = false
        val gap = compose.onNodeWithText("乙").getUnclippedBoundsInRoot().top
        items = listOf("甲", "丙")
        compose.mainClock.advanceTimeBy(FRAME_MS)
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
        items = listOf("甲", "乙", "丙", "丁")
        compose.mainClock.advanceTimeBy(FRAME_MS)
        val growing = compose.onNodeWithText("丁").getUnclippedBoundsInRoot()
        compose.mainClock.advanceTimeBy(RvMotion.ITEM_MS + FRAME_MS)
        val settled = compose.onNodeWithText("丁").getUnclippedBoundsInRoot()
        assertEquals(first, settled.top)
        assertTrue("丁 is still growing", growing.height < settled.height)
    }

    private companion object {
        const val FRAME_MS = 32L
    }
}
