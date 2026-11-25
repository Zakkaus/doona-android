package dev.doona.android.prototype.rv

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import dev.doona.android.designsystem.rv.Rv
import dev.doona.android.designsystem.rv.RvText
import dev.doona.android.designsystem.rv.rvLayout
import dev.doona.android.designsystem.rv.rvWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A wrapped [RvText] whose room changes: the steps it took to read well in one width (a narrower layout, a word brought
 * down) do not carry into another. Given more room, it is laid out as a text first laid out in that room ([rvLayout]).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvTextWrapTest {
    @get:Rule
    val compose = createComposeRule()

    private var width by mutableIntStateOf(START_PX)
    private var text by mutableIntStateOf(0)
    private lateinit var measurer: TextMeasurer
    private lateinit var style: TextStyle

    @Test
    fun aWiderRoomTakesItsOwnSteps() {
        compose.rvContent(dark = false) {
            measurer = rememberTextMeasurer()
            style = Rv.type.rowSub
            val room = with(LocalDensity.current) { width.toDp() }
            Box(Modifier.width(room)) { RvText(TEXTS[text], style, Modifier.testTag(TAG), maxLines = LINES) }
        }
        compose.waitForIdle()
        // A text and a width where a first layout takes a step (narrower, or a word brought down), and a wider room where
        // a first layout takes none.
        val (index, narrow) = requireNotNull(stepped()) { "no text takes a step in any width" }
        val wide = (narrow + 1..ROOM_PX).firstOrNull { !stepped(TEXTS[index], it) }
        text = index
        width = narrow
        compose.waitForIdle()
        val before = layout()
        assertTrue("a step taken at $narrow px", before.isNarrower(narrow) || before.layoutInput.text.text != plain(TEXTS[index]))
        width = requireNotNull(wide) { "no room up to $ROOM_PX px lays ${TEXTS[index]} out without a step" }
        compose.waitForIdle()
        val fresh = measurer.rvLayout(rvWords(TEXTS[index], LINES, true), style, LINES, wide)
        val shown = layout()
        assertEquals("at $wide px after $narrow px, the text", fresh.layoutInput.text.text, shown.layoutInput.text.text)
        assertEquals("at $wide px after $narrow px, the room it is laid out in", fresh.layoutInput.constraints.maxWidth, shown.layoutInput.constraints.maxWidth)
        assertEquals("at $wide px after $narrow px, its lines", fresh.lineCount, shown.lineCount)
    }

    /** The first text and width, in px, where a first layout takes a step; narrower ones first, as the review found. */
    private fun stepped(): Pair<Int, Int>? {
        val widths = MIN_PX..NARROW_PX step STEP_PX
        val narrower = TEXTS.indices.firstNotNullOfOrNull { i -> widths.firstOrNull { measurer.rvLayout(rvWords(TEXTS[i], LINES, true), style, LINES, it).isNarrower(it) }?.let { i to it } }
        return narrower ?: TEXTS.indices.firstNotNullOfOrNull { i -> widths.firstOrNull { stepped(TEXTS[i], it) }?.let { i to it } }
    }

    /** Whether a first layout of [text] [room] px wide takes a step: lays it out narrower, or shows it otherwise than as is. */
    private fun stepped(text: String, room: Int): Boolean {
        val layout = measurer.rvLayout(rvWords(text, LINES, true), style, LINES, room)
        return layout.isNarrower(room) || layout.layoutInput.text.text != plain(text)
    }

    /** [text] as RvText shows it with no step: the text as a measure in a room too wide to wrap it gives it back. */
    private fun plain(text: String) = measurer.rvLayout(rvWords(text, LINES, true), style, LINES, Constraints.Infinity).layoutInput.text.text

    private fun TextLayoutResult.isNarrower(room: Int) = layoutInput.constraints.maxWidth < room

    private fun layout(): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(TAG).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        return layouts.single()
    }

    private companion object {
        const val TAG = "wrapped"
        const val LINES = 4
        const val MIN_PX = 120
        const val NARROW_PX = 480
        const val STEP_PX = 2

        /** Inside the 412 dp window at xhdpi. */
        const val ROOM_PX = 700
        const val START_PX = ROOM_PX

        /** Lines saying what a choice does, as the pages wrap them. */
        val TEXTS = listOf(
            "依規則分流，其餘流量經主節點",
            "連線中斷後自動重新連線，並在通知中顯示原因",
            "在控制中心加入開關，不開啟 App 也能連線或中斷連線",
            "Show a notification when the tunnel stops, with the reason it stopped",
            "Add a switch to Quick Settings to connect or disconnect without opening the app",
            "所有流量都經過主節點，不使用規則",
            "不開啟 App 也能連線或中斷連線",
            "流量不經代理，直接連到目的地",
            "Traffic goes through the main node unless a rule sends it elsewhere",
        )
    }
}
