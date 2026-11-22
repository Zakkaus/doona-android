package dev.doona.android.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.designsystem.components.DoonaBackDisc
import dev.doona.android.designsystem.components.DoonaCard
import dev.doona.android.designsystem.components.DoonaFloatingNav
import dev.doona.android.designsystem.components.DoonaLargeTitle
import dev.doona.android.designsystem.components.DoonaNavItem
import dev.doona.android.designsystem.components.DoonaScrollPage
import dev.doona.android.designsystem.components.DoonaSectionTitle
import dev.doona.android.designsystem.components.DoonaStatRow
import dev.doona.android.designsystem.components.DoonaText
import dev.doona.android.designsystem.components.LayoutRole
import dev.doona.android.designsystem.components.LocalGlassBlur
import dev.doona.android.designsystem.components.layoutRole
import dev.doona.android.designsystem.components.navClearance
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.testing.GalleryLayout
import dev.doona.android.testing.RvSpacingRules
import dev.doona.android.testing.layoutTree
import dev.doona.android.testing.setDoonaContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import dev.doona.android.icons.R as IconsR

/**
 * The design system's spacing rules on small pages with one value planted off in each, so that a rule which stops seeing
 * its defect fails here and not silently on the gallery: every rule has a page that keeps it and one that breaks it.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xxhdpi")
class RvSpacingRulesTest {
    @get:Rule
    val compose = createComposeRule()

    private val space = DoonaTheme.spacing
    private val layout = GalleryLayout(emptySet())

    /** A list card of one row in the shape of a list row: a 40 dp leading box, the title, a value at the end. */
    @Composable
    private fun RowCard(lead: Dp = space.rowPadding, gap: Dp = space.rowGap, end: Dp = space.rowPadding, height: Dp = DoonaTheme.size.rowOneLine, bottom: Dp = 0.dp) {
        Column(Modifier.padding(space.gutter)) {
            Column(Modifier.layoutRole(LayoutRole.ListCard).fillMaxWidth().background(Color.White).padding(bottom = bottom)) {
                Row(
                    Modifier.layoutRole(LayoutRole.ListRow, mergeDescendants = true).fillMaxWidth().height(height).padding(start = lead, end = end),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(DoonaTheme.size.rowDisc).background(Color.Gray))
                    Spacer(Modifier.width(gap))
                    DoonaText("title", DoonaTheme.type.rowTitle, Modifier.weight(1f))
                    DoonaText("42 ms", DoonaTheme.type.rowValue)
                }
            }
        }
    }

    private fun rowRules(): List<String> {
        val tree = compose.layoutTree()
        return RvSpacingRules.rowHeights(tree) + RvSpacingRules.leading(tree, layout) + RvSpacingRules.trailing(tree, layout) + RvSpacingRules.cardPadding(tree)
    }

    private fun failsOnce(found: List<String>, expect: String) {
        assertEquals(found.toString(), 1, found.size)
        assertTrue(found.single(), expect in found.single())
    }

    @Test
    fun aRowOnItsRolesKeepsEveryRule() {
        compose.setDoonaContent { RowCard() }
        assertEquals(emptyList<String>(), rowRules())
    }

    @Test
    fun aRowTallerThanItsLineFails() {
        compose.setDoonaContent { RowCard(height = DoonaTheme.size.rowOneLine + 2.dp) }
        failsOnce(rowRules(), "high")
    }

    @Test
    fun aLeadingVisualOffTheCardEdgeFails() {
        compose.setDoonaContent { RowCard(lead = space.rowPadding - 4.dp) }
        failsOnce(rowRules(), "from the card's edge")
    }

    @Test
    fun aLeadingVisualTooCloseToTheTextFails() {
        compose.setDoonaContent { RowCard(gap = space.rowGap - 4.dp) }
        failsOnce(rowRules(), "from leading")
    }

    @Test
    fun trailingInkOffTheCardEdgeFails() {
        compose.setDoonaContent { RowCard(end = space.rowPadding - 4.dp) }
        failsOnce(rowRules(), "ends")
    }

    @Test
    fun aCardWhoseBottomPaddingIsNotItsTopFails() {
        compose.setDoonaContent { RowCard(bottom = 4.dp) }
        failsOnce(rowRules(), "pads")
    }

    @Composable
    private fun Title(above: Dp, below: Dp) = Column(Modifier.padding(space.gutter)) {
        Row(Modifier.layoutRole(LayoutRole.Heading).padding(top = above, bottom = below)) { DoonaText("一般", DoonaTheme.type.section) }
        DoonaCard { DoonaStatRow("下載", "1.2 GB") }
    }

    @Test
    fun theSectionTitleKeepsItsRoles() {
        compose.setDoonaContent { Column(Modifier.background(Color.White)) { DoonaSectionTitle("一般", onClick = {}) } }
        assertEquals(emptyList<String>(), RvSpacingRules.sectionTitles(compose.layoutTree(), layout))
    }

    @Test
    fun aSectionTitleCloserToWhatIsAboveFails() {
        compose.setDoonaContent { Title(space.sectionAbove - 8.dp, space.sectionBelow) }
        failsOnce(RvSpacingRules.sectionTitles(compose.layoutTree(), layout), "above")
    }

    @Test
    fun aSectionTitleCloserToItsCardFails() {
        compose.setDoonaContent { Title(space.sectionAbove, space.sectionBelow - 4.dp) }
        failsOnce(RvSpacingRules.sectionTitles(compose.layoutTree(), layout), "under")
    }

    /** A 44 dp control: with Compose's minimum it lays out a 48 dp target; without it the target is the 44 dp disc. */
    @Composable
    private fun Disc(minimum: Boolean) = Column(Modifier.background(Color.White).padding(space.gutter)) {
        val target = if (minimum) Modifier.minimumInteractiveComponentSize() else Modifier
        Box(target.size(DoonaTheme.chrome.control).background(Color.Gray).clickable {}.semantics { contentDescription = "Search" })
    }

    @Test
    fun a44DpControlWithItsTargetPasses() {
        compose.setDoonaContent { Disc(minimum = true) }
        assertEquals(emptyList<String>(), RvSpacingRules.targets(compose.layoutTree()))
    }

    @Test
    fun a44DpControlWithoutItsTargetFails() {
        compose.setDoonaContent { Disc(minimum = false) }
        failsOnce(RvSpacingRules.targets(compose.layoutTree()), "under 48")
    }

    @Test
    fun theBackDiscSitsOnTheGutter() {
        compose.setDoonaContent { Column(Modifier.background(Color.White)) { Box(Modifier.fillMaxWidth()) { DoonaBackDisc(onBack = {}) } } }
        assertEquals(emptyList<String>(), RvSpacingRules.backDisc(compose.layoutTree(), layout))
    }

    @Test
    fun aBackDiscOffTheGutterFails() {
        compose.setDoonaContent { Column(Modifier.background(Color.White)) { Box(Modifier.fillMaxWidth()) { DoonaBackDisc(onBack = {}, Modifier.offset(x = 4.dp)) } } }
        failsOnce(RvSpacingRules.backDisc(compose.layoutTree(), layout), "back disc")
    }

    /** A tab home with the nav, its content ending in [end]. */
    @Composable
    private fun Scaffold(end: @Composable () -> Dp) = CompositionLocalProvider(LocalGlassBlur provides false) {
        val items = listOf(DoonaNavItem(IconsR.drawable.sym_home, "活動"), DoonaNavItem(IconsR.drawable.sym_settings, "設定"))
        DoonaScrollPage("設定", overlay = { glass -> DoonaFloatingNav(items, 1, {}, glass, collapsed = false, Modifier.align(Alignment.BottomCenter)) }) { titleBlock ->
            DoonaLargeTitle("設定", titleBlock)
            DoonaCard { DoonaStatRow("版本", "0.2.0") }
            Spacer(Modifier.height(end()))
        }
    }

    @Test
    fun aPageEndingInTheNavClearancePasses() {
        compose.setDoonaContent { Scaffold { navClearance() } }
        assertEquals(emptyList<String>(), RvSpacingRules.navClearance(compose.layoutTree(), GalleryLayout(emptySet(), scaffold = true)))
    }

    @Test
    fun aPageEndingShortOfTheNavClearanceFails() {
        compose.setDoonaContent { Scaffold { navClearance() - 8.dp } }
        failsOnce(RvSpacingRules.navClearance(compose.layoutTree(), GalleryLayout(emptySet(), scaffold = true)), "ends with")
    }
}
