package dev.doona.android.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.designsystem.components.DoonaCheckRow
import dev.doona.android.designsystem.components.DoonaLazyPageSheet
import dev.doona.android.designsystem.components.DoonaPageSheet
import dev.doona.android.designsystem.components.LayoutRole
import dev.doona.android.designsystem.components.LayoutRoleKey
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.testing.GestureBar
import dev.doona.android.testing.SpacingRules
import dev.doona.android.testing.drawFully
import dev.doona.android.testing.layoutTree
import dev.doona.android.testing.seedColors
import dev.doona.android.testing.setDoonaContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The lazy page sheet holds a list of a few hundred rows, as the apps of a device are: it composes only the rows in view,
 * scrolls to the last, keeps the spacing rules of the page sheet, and draws its chrome. The same list in [DoonaPageSheet],
 * whose scroll gives a lazy list unbounded height, throws.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xxhdpi")
class LazyPageSheetTest {
    @get:Rule
    val compose = createComposeRule()

    private fun hex(c: Color) = "#%08X".format(c.toArgb())

    private fun listOfRows() {
        compose.setDoonaContent {
            Column(Modifier.fillMaxSize().background(DoonaTheme.colors.page)) {
                DoonaLazyPageSheet {
                    items(ROWS, key = { it }) { DoonaCheckRow(checked = it % 2 == 0, onChange = {}, title = "App $it", subtitle = "com.example.app$it") }
                }
            }
        }
    }

    @Test
    fun aFewHundredRowsComposeOnlyWhatIsInViewAndScrollToTheLast() {
        listOfRows()
        val composed = compose.onAllNodes(hasText("App ", substring = true)).fetchSemanticsNodes().size
        assertTrue("$composed of $ROWS rows are composed", composed in 1..MOST_IN_VIEW)
        compose.onNode(hasScrollAction()).performScrollToIndex(ROWS - 1)
        compose.waitForIdle()
        compose.onNodeWithText("App ${ROWS - 1}", substring = true).assertIsDisplayed()
        // The layout probe reads the nodes a lazy list keeps for reuse, which are detached, so the sheet and the row are read from the semantics.
        val sheet = compose.onNode(SemanticsMatcher("the sheet") { it.config.getOrNull(LayoutRoleKey) == LayoutRole.Sheet }).fetchSemanticsNode().boundsInRoot
        val last = compose.onNodeWithText("App ${ROWS - 1}", substring = true).fetchSemanticsNode().boundsInRoot
        val below = DoonaTheme.spacing.sheetBottom.value + GestureBar.value
        assertEquals("space below the last row at the end of the list, in dp: the sheet's bottom padding and the gesture bar", below, (sheet.bottom - last.bottom) / compose.density.density, 0.5f)
    }

    @Test
    fun theSheetKeepsTheRulesOfThePageSheet() {
        listOfRows()
        val tree = compose.layoutTree()
        assertEquals(emptyList<String>(), SpacingRules.sheet(tree))
        assertEquals(emptyList<String>(), SpacingRules.contentColumn(tree))
        assertEquals(emptyList<String>(), SpacingRules.rows(tree))
    }

    /** The top corners are the sheet's 16 dp round, and the fill is the surface, as the page sheet draws them. */
    @Test
    fun theSheetDrawsTheSurfaceWithRoundedTopCorners() {
        listOfRows()
        val c = seedColors(false)
        compose.drawFully()
        val image = compose.onRoot().captureToImage().toPixelMap()
        val sheet = compose.layoutTree().descendants().first { it.role == LayoutRole.Sheet }
        val density = compose.density.density
        val top = (sheet.top * density).toInt()
        assertEquals("the corner is clipped to the window", hex(c.page), hex(image[0, top]))
        assertEquals("the sheet is the surface beside its rows", hex(c.page), hex(image[2, top + (sheet.height * density / 2).toInt()]))
    }

    /** The premise of the lazy sheet: a lazy list in the scrolling page sheet cannot be measured. */
    @Test
    fun aLazyListInThePageSheetThrows() {
        try {
            compose.setDoonaContent {
                Column(Modifier.fillMaxSize()) {
                    DoonaPageSheet { LazyColumn { items(ROWS) { DoonaCheckRow(checked = false, onChange = {}, title = "App $it") } } }
                }
            }
            compose.waitForIdle()
            fail("a lazy list in the scrolling sheet was measured")
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty(), "infinity maximum height" in e.message.orEmpty())
        }
        assertFalse(compose.onAllNodes(hasText("App 0")).fetchSemanticsNodes().isNotEmpty())
    }

    private companion object {
        /** The apps of a device run to a few hundred. */
        const val ROWS = 300

        /** A phone shows about fifteen rows; far fewer than all of them. */
        const val MOST_IN_VIEW = 40
    }
}
