package dev.doona.android.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.R
import dev.doona.android.designsystem.components.DoonaCard
import dev.doona.android.designsystem.components.DoonaGap
import dev.doona.android.designsystem.components.DoonaHGap
import dev.doona.android.designsystem.components.DoonaIcon
import dev.doona.android.designsystem.components.DoonaPage
import dev.doona.android.designsystem.components.DoonaPageTabs
import dev.doona.android.designsystem.components.DoonaPageTitle
import dev.doona.android.designsystem.components.DoonaRow
import dev.doona.android.designsystem.components.DoonaTagChip
import dev.doona.android.designsystem.components.DoonaText
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.testing.SpacingRules
import dev.doona.android.testing.layoutTree
import dev.doona.android.testing.readWhileScrolling
import dev.doona.android.testing.setDoonaContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

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
            DoonaCard(Modifier.fillMaxWidth()) { DoonaText("one", DoonaTheme.type.body) }
            DoonaCard(Modifier.fillMaxWidth()) { DoonaText("two", DoonaTheme.type.body) }
        }
        DoonaGap(before)
        DoonaCard(Modifier.fillMaxWidth()) { DoonaText("tiles", DoonaTheme.type.body) }
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
        DoonaCard(Modifier.fillMaxWidth()) { DoonaText("one", DoonaTheme.type.body) }
        DoonaCard(Modifier.fillMaxWidth()) { DoonaText("two", DoonaTheme.type.body) }
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

    @Composable
    private fun IconRow(gap: Dp) = DoonaCard(Modifier.fillMaxWidth()) {
        DoonaRow {
            DoonaIcon(R.drawable.ic_search, DoonaTheme.colors.accent)
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
     * Two chips 4 dp apart under 600 dp of space: hit over 48 dp each, their areas overlap by 8 dp. Below the fold the
     * unscrolled page does not show them whole and the rule skips them; a scroll shows them and fails them.
     */
    @Test
    fun touchAreasBelowTheFoldAreChecked() {
        compose.setDoonaContent {
            Box(Modifier.height(320.dp)) {
                DoonaPage {
                    Spacer(Modifier.height(600.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(space.tight)) {
                        DoonaTagChip("a", onClick = {})
                        DoonaTagChip("b", onClick = {})
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
                DoonaPage { DoonaTagChip("a", onClick = {}) }
            }
        }
        val first = compose.readWhileScrolling { SpacingRules.touch(it) }
        // A report that saw no action whole, as when a scroll misses a block: the action is named as never checked.
        val blind = listOf(SpacingRules.TouchReport(emptyList(), emptySet(), first.first().actions))
        assertEquals(1, SpacingRules.unseen(blind).size)
    }
}
