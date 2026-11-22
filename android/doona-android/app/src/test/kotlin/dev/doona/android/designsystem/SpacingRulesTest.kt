package dev.doona.android.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.designsystem.components.DoonaFilterPicker
import dev.doona.android.designsystem.components.DoonaGap
import dev.doona.android.designsystem.components.DoonaHGap
import dev.doona.android.designsystem.components.DoonaHubShell
import dev.doona.android.designsystem.components.DoonaHubTab
import dev.doona.android.designsystem.components.DoonaIcon
import dev.doona.android.designsystem.components.DoonaPage
import dev.doona.android.designsystem.components.DoonaPageTabs
import dev.doona.android.designsystem.components.DoonaPageTitle
import dev.doona.android.designsystem.components.DoonaText
import dev.doona.android.designsystem.components.InsetCard
import dev.doona.android.designsystem.components.LayoutRole
import dev.doona.android.designsystem.components.layoutRole
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.testing.Landscape
import dev.doona.android.testing.SpacingRules
import dev.doona.android.testing.layoutTree
import dev.doona.android.testing.readWhileScrolling
import dev.doona.android.testing.setDoonaContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import dev.doona.android.icons.R as IconsR

/**
 * The spacing rules on small pages with a defect planted in each, so that a rule which stops seeing its defect fails
 * here and not silently on the screens: every case has a page that keeps the rule and one that breaks it.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xxhdpi")
class SpacingRulesTest {
    @get:Rule
    val compose = createComposeRule()

    private val space = DoonaTheme.spacing

    /** A page of the activity screen's shape: the title and tabs, a stack of cards, then a section [before] its last card. */
    @Composable
    private fun Page(before: Dp) = DoonaPage {
        DoonaPageTitle("Title")
        DoonaGap(space.afterTitle)
        DoonaPageTabs(listOf("A", "B"), selected = 0)
        DoonaGap(space.section)
        Column(verticalArrangement = Arrangement.spacedBy(space.block)) {
            InsetCard(Modifier.fillMaxWidth()) { DoonaText("one", DoonaTheme.type.body) }
            InsetCard(Modifier.fillMaxWidth()) { DoonaText("two", DoonaTheme.type.body) }
        }
        DoonaGap(before)
        InsetCard(Modifier.fillMaxWidth()) { DoonaText("tiles", DoonaTheme.type.body) }
    }

    private val rhythm get() = listOf(space.afterTitle, space.section, space.section)

    @Test
    fun theSectionGapKeepsItsRole() {
        compose.setDoonaContent { Page(space.section) }
        assertEquals(emptyList<String>(), SpacingRules.rhythm(compose.layoutTree(), rhythm))
    }

    /** A block gap is on the scale, so a check of "any page gap" passes it: only the role says it is the wrong gap. */
    @Test
    fun aBlockGapWhereASectionBelongsFails() {
        compose.setDoonaContent { Page(space.block) }
        val tree = compose.layoutTree()
        assertEquals(emptyList<String>(), SpacingRules.scaleGaps(tree))
        val found = SpacingRules.rhythm(tree, rhythm)
        assertEquals(found.toString(), 1, found.size)
        assertTrue(found.single(), "sheet gap 2" in found.single())
    }

    @Test
    fun aMissingBlockFails() {
        compose.setDoonaContent { Page(space.section) }
        assertEquals(1, SpacingRules.rhythm(compose.layoutTree(), rhythm.dropLast(1)).size)
    }

    @Composable
    private fun Cards(gap: Dp) = Column(verticalArrangement = Arrangement.spacedBy(gap)) {
        InsetCard(Modifier.fillMaxWidth()) { DoonaText("one", DoonaTheme.type.body) }
        InsetCard(Modifier.fillMaxWidth()) { DoonaText("two", DoonaTheme.type.body) }
    }

    @Test
    fun cardsAreABlockApart() {
        compose.setDoonaContent { Cards(space.block) }
        assertEquals(emptyList<String>(), SpacingRules.stacks(compose.layoutTree()))
    }

    @Test
    fun cardsAnItemApartFail() {
        compose.setDoonaContent { Cards(space.item) }
        assertEquals(1, SpacingRules.stacks(compose.layoutTree()).size)
    }

    /** Two blocks that overlap by 2 dp: the column has a gap of -2, and it is not on the scale. */
    @Test
    fun anOverlapIsAGapBelowZero() {
        compose.setDoonaContent {
            Column(Modifier.padding(space.gutter), verticalArrangement = Arrangement.spacedBy((-2).dp)) {
                Box(Modifier.fillMaxWidth().height(40.dp).background(Color.Gray))
                Box(Modifier.fillMaxWidth().height(40.dp).background(Color.DarkGray))
            }
        }
        val found = SpacingRules.scaleGaps(compose.layoutTree())
        assertEquals(found.toString(), 1, found.size)
        assertTrue(found.single(), "-2.0" in found.single())
    }

    /** A row that reports itself narrower than its content and places the content that far left, as the latency dot once did. */
    @Composable
    private fun Hanging(lead: Dp) {
        val px = with(LocalDensity.current) { lead.roundToPx() }
        Row(
            Modifier.layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(placeable.width - px, placeable.height) { placeable.place(-px, 0) }
            },
        ) {
            Box(Modifier.size(8.dp).background(DoonaTheme.colors.positive))
            DoonaHGap(space.dotGap)
            DoonaText("42", DoonaTheme.type.bodyStrong)
        }
    }

    @Test
    fun nothingDrawsOutsideItsParent() {
        compose.setDoonaContent { Column(Modifier.padding(space.section)) { Hanging(0.dp) } }
        assertEquals(emptyList<String>(), SpacingRules.contained(compose.layoutTree()))
    }

    @Test
    fun aPartHangingOutsideItsParentFails() {
        compose.setDoonaContent { Column(Modifier.padding(space.section)) { Hanging(12.dp) } }
        val found = SpacingRules.contained(compose.layoutTree())
        assertTrue(found.toString(), found.isNotEmpty())
    }

    /** A control is as high as its track exactly; a button is at least that high, so a label that wrapped does not fail it. */
    @Composable
    private fun Controls(control: Dp, button: Dp) = Column(Modifier.padding(space.gutter), verticalArrangement = Arrangement.spacedBy(space.section)) {
        Box(Modifier.layoutRole(LayoutRole.Control).fillMaxWidth().height(control).background(Color.Gray))
        Box(Modifier.layoutRole(LayoutRole.Button).fillMaxWidth().height(button).background(Color.DarkGray))
    }

    @Test
    fun aButtonTallerThanTheControlKeepsTheRule() {
        compose.setDoonaContent { Controls(DoonaTheme.size.control, DoonaTheme.size.control * 2) }
        assertEquals(emptyList<String>(), SpacingRules.controls(compose.layoutTree()))
    }

    @Test
    fun aButtonShorterThanTheControlFails() {
        compose.setDoonaContent { Controls(DoonaTheme.size.control, DoonaTheme.size.control - 4.dp) }
        val found = SpacingRules.controls(compose.layoutTree())
        assertEquals(found.toString(), 1, found.size)
        assertTrue(found.single(), found.single().startsWith("button"))
    }

    @Test
    fun aControlTallerThanItsTrackFails() {
        compose.setDoonaContent { Controls(DoonaTheme.size.control + 8.dp, DoonaTheme.size.control) }
        val found = SpacingRules.controls(compose.layoutTree())
        assertEquals(found.toString(), 1, found.size)
        assertTrue(found.single(), found.single().startsWith("control"))
    }

    @Composable
    private fun IconRow(gap: Dp) = InsetCard(Modifier.fillMaxWidth()) {
        Row(Modifier.layoutRole(LayoutRole.Row, mergeDescendants = true).heightIn(min = DoonaTheme.size.touch), verticalAlignment = Alignment.CenterVertically) {
            DoonaIcon(IconsR.drawable.sym_search, DoonaTheme.colors.positive)
            DoonaHGap(gap)
            DoonaText("label", DoonaTheme.type.body)
        }
    }

    @Test
    fun anIconIsAnItemFromItsLabel() {
        compose.setDoonaContent { IconRow(space.item) }
        assertEquals(emptyList<String>(), SpacingRules.rows(compose.layoutTree()))
    }

    @Test
    fun anIconABlockFromItsLabelFails() {
        compose.setDoonaContent { IconRow(space.block) }
        assertEquals(1, SpacingRules.rows(compose.layoutTree()).size)
    }

    /**
     * Two pickers 4 dp apart under 600 dp of space: hit over 48 dp each, their areas overlap by 8 dp. Below the fold the
     * unscrolled page does not show them whole and the rule skips them; a scroll shows them and fails them.
     */
    @Test
    fun touchAreasBelowTheFoldAreChecked() {
        compose.setDoonaContent {
            Box(Modifier.height(320.dp)) {
                DoonaPage {
                    Spacer(Modifier.height(600.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(space.tight)) {
                        DoonaFilterPicker("a", onClick = {})
                        DoonaFilterPicker("b", onClick = {})
                    }
                }
            }
        }
        val reports = compose.readWhileScrolling { SpacingRules.touch(it) }
        assertEquals("the unscrolled page shows nothing wrong", emptyList<String>(), reports.first().violations)
        assertTrue("a scroll shows the chips whole, with areas that overlap", reports.drop(1).any { r -> r.violations.any { "touch area" in it } })
        assertEquals("every action was in view whole at some offset", emptyList<String>(), SpacingRules.unseen(reports))
    }

    @Test
    fun anActionThatNoOffsetShowsWholeIsReported() {
        compose.setDoonaContent {
            Box(Modifier.height(320.dp)) {
                DoonaPage { DoonaFilterPicker("a", onClick = {}) }
            }
        }
        val first = compose.readWhileScrolling { SpacingRules.touch(it) }
        // A report that saw no action whole, as when a scroll misses a block: the action is named as never checked.
        val blind = listOf(SpacingRules.TouchReport(emptyList(), emptySet(), first.first().actions))
        assertEquals(1, SpacingRules.unseen(blind).size)
    }

    private val rail = listOf(DoonaHubTab("One", IconsR.drawable.sym_speed), DoonaHubTab("Two", IconsR.drawable.sym_link))

    /** A window shorter than the compact height swaps the bottom bar for a rail, and the page's column starts next to it. */
    @Test
    fun aShortWindowStartsTheColumnNextToTheRail() {
        RuntimeEnvironment.setQualifiers(Landscape.qualifiers)
        compose.setDoonaContent { DoonaHubShell(rail, selected = 0, onSelect = {}) { DoonaPage { DoonaPageTitle("Title") } } }
        val tree = compose.layoutTree()
        assertEquals(emptyList<String>(), SpacingRules.contentColumn(tree))
        val sheet = tree.descendants().first { it.role == LayoutRole.Sheet }
        assertEquals("the column starts at the rail's edge, 80 dp in", DoonaTheme.size.railWidth.value, sheet.left, 0.5f)
    }

    /** The column centred in what the rail leaves, as it was, puts a band of empty sheet before the content: the rule sees it. */
    @Test
    fun aCentredColumnInAShortWindowFails() {
        RuntimeEnvironment.setQualifiers(Landscape.qualifiers)
        compose.setDoonaContent {
            Box(Modifier.fillMaxSize().background(Color.Gray), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.layoutRole(LayoutRole.Sheet).widthIn(max = DoonaTheme.size.contentMax)) { DoonaText("Title", DoonaTheme.type.body) }
            }
        }
        val found = SpacingRules.contentColumn(compose.layoutTree())
        assertEquals(found.toString(), 1, found.size)
        assertTrue(found.single(), "short window" in found.single())
    }

    /** On a window tall enough for a bottom bar the column is still centred: a start-aligned one fails. */
    @Test
    fun aStartAlignedColumnInATallWindowFails() {
        compose.setDoonaContent {
            Box(Modifier.fillMaxSize().background(Color.Gray), contentAlignment = Alignment.TopStart) {
                Column(Modifier.layoutRole(LayoutRole.Sheet).widthIn(max = 300.dp)) { DoonaText("Title", DoonaTheme.type.body) }
            }
        }
        val found = SpacingRules.contentColumn(compose.layoutTree())
        assertEquals(found.toString(), 1, found.size)
        assertTrue(found.single(), "not centred" in found.single())
    }
}
