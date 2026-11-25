package dev.doona.android.designsystem

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.designsystem.components.LayoutRole
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.testing.FontScale200
import dev.doona.android.testing.Landscape
import dev.doona.android.testing.LayoutBox
import dev.doona.android.testing.Light
import dev.doona.android.testing.SpacingRules
import dev.doona.android.testing.layoutTree
import dev.doona.android.testing.screenCases
import dev.doona.android.testing.setVariantContent
import dev.doona.android.testing.sheetScroll
import dev.doona.android.testing.tall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val TOL = 0.5f

/** The hub navigation: a bar that grows with a larger font, and what it becomes in a window too short for it. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class HubNavigationTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theBarGrowsWithTheFontAndKeepsAirAroundItsTabs() {
        compose.setVariantContent(FontScale200) { screenCases().first { it.name == "policies" }.content() }
        val tree = compose.layoutTree()
        val bar = tree.descendants().first { it.role == LayoutRole.HubBar }
        assertTrue("the bar is %.1f dp high, which is no more than its minimum of %.0f".format(bar.height, DoonaTheme.size.bottomBar.value), bar.height > DoonaTheme.size.bottomBar.value + TOL)
        val broken = SpacingRules.hubBar(tree)
        assertTrue("a hub bar at twice the font size breaks its rules:\n" + broken.joinToString("\n"), broken.isEmpty())
        assertEquals("the bar still ends on the gesture bar", 24f, tree.bottom - bar.bottom, TOL)
    }

    private fun navigation(tree: LayoutBox) = tree.descendants().filter { it.role == LayoutRole.HubBar }.toList()

    @Test
    fun aTallWindowKeepsTheBarAtTheBottom() {
        compose.setVariantContent(Light) { screenCases().first { it.name == "policies" }.content() }
        val tree = compose.layoutTree()
        val bar = navigation(tree).single()
        assertTrue("the hub navigation of a tall window is a row", !bar.vertical)
        assertEquals("the bar ends on the gesture bar", 24f, tree.bottom - bar.bottom, TOL)
    }

    @Test
    fun aShortWindowHasARailAtTheStartEdgeAndNoBottomBar() {
        compose.setVariantContent(Landscape) { screenCases().first { it.name == "policies" }.content() }
        val tree = compose.layoutTree()
        val rail = navigation(tree).single()
        val sheet = tree.descendants().first { it.role == LayoutRole.Sheet }
        assertTrue("the hub navigation of a short window is a column", rail.vertical)
        assertEquals("the rail starts at the window's start edge", 0f, rail.left - tree.left, TOL)
        assertEquals("the rail is this wide", DoonaTheme.size.railWidth.value, rail.width, TOL)
        assertTrue("the page lies beside the rail: its sheet starts at ${sheet.left}, the rail ends at ${rail.right}", sheet.left >= rail.right - TOL)
        assertEquals("the page runs to the bottom of the window", tree.bottom, tree.viewportOf(sheet).bottom, TOL)
        val broken = SpacingRules.hubBar(tree)
        assertTrue("the rail breaks the hub rules:\n" + broken.joinToString("\n"), broken.isEmpty())
    }

    @Test
    fun aWindowOneDpUnderTheCompactHeightHasARail() {
        compose.setVariantContent(Light.tall(479)) { screenCases().first { it.name == "settings" }.content() }
        assertTrue("a window 479 dp high has a rail", navigation(compose.layoutTree()).single().vertical)
    }

    @Test
    fun aWindowAtTheCompactHeightHasABar() {
        compose.setVariantContent(Light.tall(480)) { screenCases().first { it.name == "settings" }.content() }
        assertTrue("a window 480 dp high has a bar", !navigation(compose.layoutTree()).single().vertical)
    }

    @Test
    fun theTitleAndTheTabsScrollAwayWithTheContentInAShortWindow() {
        compose.setVariantContent(Landscape) { screenCases().first { it.name == "policies" }.content() }
        val density = compose.density.density
        val title = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        val before = title.fetchSemanticsNode().boundsInRoot
        compose.onNode(sheetScroll()).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
        compose.waitForIdle()
        val tree = compose.layoutTree()
        val viewport = tree.viewportOf(tree.descendants().first { it.role == LayoutRole.Sheet })
        val after = title.fetchSemanticsNode().boundsInRoot
        assertTrue("the title starts in view at ${before.top / density}", before.top / density >= viewport.top - TOL)
        assertTrue("the title is still in view after the scroll, at ${after.bottom / density}", after.bottom / density < viewport.top + TOL)
    }

    @Test
    fun besideARailTheSheetClearsTheGestureBar() {
        compose.setVariantContent(Landscape) { screenCases().first { it.name == "group" }.content() }
        compose.onNode(sheetScroll()).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
        compose.waitForIdle()
        val sheet = compose.layoutTree().descendants().first { it.role == LayoutRole.Sheet }
        assertEquals("space below the last block: the sheet's end and the gesture bar's inset", 40f + 24f, sheet.bottom - sheet.content.last().bottom, TOL)
    }
}
