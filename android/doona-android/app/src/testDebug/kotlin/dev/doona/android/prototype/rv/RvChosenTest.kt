package dev.doona.android.prototype.rv

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.doona.android.R
import dev.doona.android.designsystem.rv.Rv
import dev.doona.android.designsystem.rv.RvCaption
import dev.doona.android.designsystem.rv.RvChartSize
import dev.doona.android.designsystem.rv.RvChrome
import dev.doona.android.designsystem.rv.RvPart
import dev.doona.android.designsystem.rv.RvRangeRow
import dev.doona.android.designsystem.rv.RvRowPart
import dev.doona.android.designsystem.rv.RvSegmented
import dev.doona.android.designsystem.rv.RvSkeletonChips
import dev.doona.android.designsystem.rv.rvCheckReserve
import dev.doona.android.designsystem.rv.rvPress
import dev.doona.android.prototype.rv.data.RvData
import dev.doona.android.prototype.rv.data.RvNodeSort
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The chosen segment or range chip is bold after a check (owner, 2026-11-25, q-a11y-selected): the weight moves no pill,
 * and of a range row only the chosen chip's check room does.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RV_EN)
class RvChosenTest {
    @get:Rule
    val compose = createComposeRule()

    /** The skeleton's shimmer runs forever; its test stops animators, and the next test gets them back. */
    @After
    fun restoreScale() = rvAnimatorScale(1f)

    private val options = listOf("Both", "TCP", "UDP")
    private val ranges = listOf("1H", "1D", "1W", "1M", "1Y")

    /** The choice (a tab of the merged tree) that holds a label of [text]. */
    private fun tab(text: String): SemanticsNode =
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab) and hasText(text)).fetchSemanticsNode()

    private fun weightOf(text: String): FontWeight? {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNode(hasText(text), useUnmergedTree = true).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        return layouts.single().layoutInput.style.fontWeight
    }

    /** How many glyphs (the check, or a segment's own glyph) sit inside the choice that holds [text]. */
    private fun checks(text: String): Int = compose.onAllNodesWithTag(RvRowPart.GLYPH, useUnmergedTree = true).fetchSemanticsNodes().count { glyph ->
        val box = tab(text).boundsInRoot
        box.contains(glyph.boundsInRoot.center)
    }

    @Test
    fun theChosenSegmentIsBoldAfterACheckAndNoSegmentMoves() {
        var chosen by mutableIntStateOf(0)
        compose.rvContent(dark = false) { RvSegmented(options, chosen, { chosen = it }) }
        val before = options.map { tab(it).boundsInRoot }
        assertEquals(FontWeight.Bold, weightOf(options[0]))
        assertNotEquals("the others are not bold", FontWeight.Bold, weightOf(options[1]))
        assertEquals(listOf(1, 0, 0), options.map { checks(it) })
        compose.runOnIdle { chosen = 2 }
        compose.waitForIdle()
        assertEquals("no segment changes its size or place", before, options.map { tab(it).boundsInRoot })
        assertEquals(FontWeight.Bold, weightOf(options[2]))
        assertNotEquals(FontWeight.Bold, weightOf(options[0]))
        assertEquals(listOf(0, 0, 1), options.map { checks(it) })
    }

    @Test
    fun theChosenRangeChipIsBoldAfterACheckAndOnlyItsCheckMovesThePills() {
        var chosen by mutableIntStateOf(1)
        compose.rvContent(dark = false) { RvRangeRow(ranges, chosen, { chosen = it }) }
        val before = compose.onAllNodesWithTag(RvPart.FRAME, useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot.width }
        assertEquals(ranges.size, before.size)
        assertEquals(FontWeight.Bold, weightOf(ranges[1]))
        assertNotEquals("the others are not bold", FontWeight.Bold, weightOf(ranges[0]))
        assertEquals(listOf(0, 1, 0, 0, 0), ranges.map { checks(it) })
        compose.runOnIdle { chosen = 4 }
        compose.waitForIdle()
        val after = compose.onAllNodesWithTag(RvPart.FRAME, useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot.width }
        assertEquals(listOf(0, 0, 0, 0, 1), ranges.map { checks(it) })
        // Every chip is as wide as its bold label, so the weight changes no width; only the check's room moves.
        val room = with(compose.density) { rvCheckReserve.toPx() }
        val expected = before.mapIndexed { i, w ->
            w + room * (
                if (i == 4) {
                    1
                } else if (i == 1) {
                    -1
                } else {
                    0
                }
                )
        }
        expected.zip(after).forEach { (e, a) -> assertEquals("a chip's width changes by the check's room only", e, a, 1f) }
    }

    /**
     * The skeleton's chips stand where the real ones do, for every order the row can have chosen (a11y-fix2, 2026-11-25:
     * it reserved the check on the first chip whatever the page had chosen, so a reload under 穩定, 名稱 or 地區 moved the
     * chips by the check's room when the data arrived).
     */
    private fun skeletonChipsStandWhereTheRealOnesDo() {
        rvAnimatorScale(0f)
        var chosen by mutableIntStateOf(0)
        compose.rvContent(dark = false) {
            val labels = RvNodeSort.entries.map { stringResource(it.label) }
            Column {
                RvRangeRow(labels, chosen, {})
                RvSkeletonChips(labels, chosen)
            }
        }
        RvNodeSort.entries.forEach { sort ->
            compose.runOnIdle { chosen = sort.ordinal }
            compose.waitForIdle()
            val real = chips()
            val skeleton = chips(RvPart.SKELETON_BLOCK)
            assertEquals("a skeleton chip for every chip", real.size, skeleton.size)
            real.zip(skeleton).forEachIndexed { i, (r, s) ->
                assertEquals("$sort: chip $i's left", r.first, s.first, 1f)
                assertEquals("$sort: chip $i's width", r.second, s.second, 1f)
            }
        }
    }

    @Test
    fun theSkeletonChipsStandWhereTheRealOnesDoInEn() = skeletonChipsStandWhereTheRealOnesDo()

    @Test
    @Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
    fun theSkeletonChipsStandWhereTheRealOnesDoInZhTw() = skeletonChipsStandWhereTheRealOnesDo()

    /** 流量's range row keeps the chosen chip's check at the default size in every locale (a11y-fix, 2026-11-25). */
    private fun trafficKeepsTheCheck(first: String) {
        compose.rvContent(dark = false) { RvTrafficScreen(rvSampleActivity(), RvData.session, onBack = {}) }
        assertEquals(FontWeight.Bold, weightOf(first))
        assertEquals("the chosen range has its check", 1, checks(first))
    }

    @Test
    @Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
    fun trafficRangesKeepTheCheckInZhTw() = trafficKeepsTheCheck("1 分")

    @Test
    @Config(qualifiers = "zh-rCN-w412dp-h915dp-notnight-xhdpi")
    fun trafficRangesKeepTheCheckInZhCn() = trafficKeepsTheCheck("1 分")

    @Test
    fun trafficRangesKeepTheCheckInEn() = trafficKeepsTheCheck("1m")
}
