package dev.doona.android.prototype.rv

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.height
import dev.doona.android.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Drag to reorder (brief rv-gestures G4) on six rows of a card in a scrolling page, on a clock the test drives: a long
 * press lifts a row at 500 ms and not before, with a haptic tick; dragged below its second neighbour and let go it lands
 * there with another tick and the page hears of the move once; a long press that does not move changes nothing;
 * TalkBack's 下移 moves a row by one; at animator scale 0 it lands at once.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvReorderTest {
    @get:Rule
    val compose = createComposeRule()

    private val taps = RvTaps()
    private val items = mutableStateListOf(1, 2, 3, 4, 5, 6)
    private var moves = 0

    @After
    fun animatorScaleBack() = rvAnimatorScale(1f)

    @Test
    fun theLiftNeedsTheFullLongPress() {
        harness()
        row(2).performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(BEFORE_MS)
        assertEquals("nothing lifts at 450 ms", emptyList<HapticFeedbackType>(), taps.kinds)
        compose.mainClock.advanceTimeBy(AFTER_MS - BEFORE_MS)
        assertEquals("lifted at 550 ms", listOf(HapticFeedbackType.LongPress), taps.kinds)
        row(2).performTouchInput { up() }
        compose.mainClock.advanceTimeBy(RvMotion.MOVE_MS * 2L)
    }

    @Test
    fun aRowDraggedBelowItsSecondNeighbourLandsThere() {
        harness()
        val rest = top(2)
        val height = row(2).getUnclippedBoundsInRoot().height
        dragRow2(release = false)
        compose.mainClock.advanceTimeByFrame()
        val held = top(2) - rest
        assertEquals("the held row follows the finger 1:1", height.value * DRAG_ROWS, held.value, NEAR)
        assertEquals("nothing moves in the list while it is held", 0, moves)
        row(2).performTouchInput { up() }
        compose.mainClock.advanceTimeBy(RvMotion.MOVE_MS * 2L)
        assertEquals(listOf(1, 3, 4, 2, 5, 6), items.toList())
        assertEquals("the page hears of the move once", 1, moves)
        assertEquals(listOf(HapticFeedbackType.LongPress, HapticFeedbackType.GestureEnd), taps.kinds)
        val order = items.map { top(it).value }
        assertEquals("the rows are drawn in the new order", order.sorted(), order)
    }

    @Test
    fun aLongPressThatDoesNotMoveChangesNothing() {
        harness()
        row(2).performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(AFTER_MS)
        row(2).performTouchInput { up() }
        compose.mainClock.advanceTimeBy(RvMotion.MOVE_MS * 2L)
        assertEquals(listOf(1, 2, 3, 4, 5, 6), items.toList())
        assertEquals(0, moves)
    }

    @Test
    fun talkBacksMoveDownMovesARowByOne() {
        harness()
        val actions = row(2).fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertEquals(listOf(R.string.rv_reorder_up, R.string.rv_reorder_down).map(::rvText), actions.map { it.label })
        compose.runOnIdle { actions.first { it.label == rvText(R.string.rv_reorder_down) }.action() }
        compose.mainClock.advanceTimeBy(RvMotion.MOVE_MS * 2L)
        assertEquals(listOf(1, 3, 2, 4, 5, 6), items.toList())
        assertEquals(1, moves)
        val first = row(1).fetchSemanticsNode().config[SemanticsActions.CustomActions].map { it.label }
        assertEquals("the first row cannot go up", listOf(rvText(R.string.rv_reorder_down)), first)
    }

    @Test
    fun atAnimatorScaleZeroItLandsAtOnce() {
        rvAnimatorScale(0f)
        harness()
        dragRow2(release = true)
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        assertEquals(listOf(1, 3, 4, 2, 5, 6), items.toList())
        assertEquals("the haptics still fire", listOf(HapticFeedbackType.LongPress, HapticFeedbackType.GestureEnd), taps.kinds)
    }

    @Test
    fun aTouchWhileItLandsCatchesIt() {
        harness()
        dragRow2(release = true)
        compose.mainClock.advanceTimeBy(RvMotion.MOVE_MS / 4L)
        row(3).performTouchInput { down(center) }
        compose.mainClock.advanceTimeByFrame()
        val caught = top(2)
        compose.mainClock.advanceTimeBy(RvMotion.MOVE_MS * 2L)
        assertEquals("a finger holds it where it was", caught.value, top(2).value, NEAR)
        assertEquals("nothing is reported while it is held", 0, moves)
        row(3).performTouchInput { up() }
        compose.mainClock.advanceTimeBy(RvMotion.MOVE_MS * 2L)
        assertEquals(1, moves)
        assertTrue("it still lands below row 4: $items", items.indexOf(2) > items.indexOf(4))
    }

    /** Six labelled rows in an [RvCard] in a scrolling page, the reorder wired to [items]. */
    private fun harness() {
        compose.setContent {
            RvTheme(seedColorScheme(Seed.Cyan.argb, false), false) {
                CompositionLocalProvider(LocalHapticFeedback provides taps) { Rows() }
            }
        }
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeByFrame()
    }

    @Composable
    private fun Rows() {
        val scroll = rememberScrollState()
        val state = rememberRvReorderState(scroll, items.size) { from, to ->
            moves++
            items.add(to, items.removeAt(from))
        }
        Column(Modifier.fillMaxSize().rvReorderViewport(state).verticalScroll(scroll)) {
            RvCard {
                items.forEachIndexed { index, item ->
                    key(item) { RvRow({ RvIconDisc(R.drawable.rv_sym_info) }, label(item), null, Modifier.rvReorder(state, index), onClick = {}) }
                }
            }
        }
    }

    /** Long-presses row 2 and drags it [DRAG_ROWS] rows down, slowly, letting go when [release]. */
    private fun dragRow2(release: Boolean) {
        row(2).performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(AFTER_MS)
        val height = row(2).getUnclippedBoundsInRoot().height
        row(2).performTouchInput {
            val step = height.toPx() * DRAG_ROWS / STEPS
            repeat(STEPS) { moveBy(Offset(0f, step), delayMillis = FRAME_MS) }
            if (release) up()
        }
    }

    private fun label(item: Int) = "Row $item"

    private fun row(item: Int) = compose.onNodeWithText(label(item))

    private fun top(item: Int) = row(item).getUnclippedBoundsInRoot().top

    private companion object {
        const val BEFORE_MS = 450L
        const val AFTER_MS = 550L

        /** Past the midpoints of rows 3 and 4, short of row 5's. */
        const val DRAG_ROWS = 2.2f
        const val STEPS = 20
        const val FRAME_MS = 16L
        const val NEAR = 1.5f
    }
}
