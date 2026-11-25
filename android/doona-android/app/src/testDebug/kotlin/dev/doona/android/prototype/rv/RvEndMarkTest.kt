package dev.doona.android.prototype.rv

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RvCard
import dev.doona.android.designsystem.rv.RvEndMark
import dev.doona.android.designsystem.rv.RvIconDisc
import dev.doona.android.designsystem.rv.RvLatencyChange
import dev.doona.android.designsystem.rv.RvRow
import dev.doona.android.designsystem.rv.RvRowEnd
import dev.doona.android.designsystem.rv.RvRowPart
import dev.doona.android.designsystem.rv.RvSize
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.designsystem.rv.RvTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * An end's glyph button ([RvEndMark], 節點's info mark; LAYOUT-RULES.md R21): the row's trailing room holds the value, the
 * 12 dp gap and the 22 dp glyph, no more (the 48 dp box it replaced took 26 dp more of the text's width), while its
 * target stays 48 dp round the glyph and answers a tap near its edge.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h400dp-notnight-xhdpi")
class RvEndMarkTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theMarkTakesItsInkAndKeepsItsTarget() {
        var opened = 0
        compose.setContent {
            RvTheme(seedColorScheme(Seed.Cyan.argb, false), false) {
                Column {
                    Spacer(Modifier.height(EDGE.dp))
                    RvCard {
                        RvRow({ RvIconDisc(R.drawable.rv_sym_rule) }, TITLE, SUBTITLE) {
                            RvRowEnd({ RvLatencyChange(LATENCY, null) }) { RvEndMark(R.drawable.rv_sym_info, DETAILS) { opened++ } }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val nodes = compose.rvRowNodes()
        val room = nodes.single { it.tag == RvRowPart.END }.box
        val target = nodes.single { it.tag == RvRowPart.TARGET }.box
        assertEquals("the target is 48 dp", RvSize.touch.value, target.width, HALF)
        assertEquals("the target is centred on the glyph, which ends at the room's end", room.right - RvSize.rowGlyph.value / 2, target.center.x, HALF)
        // The value's figure: the widest text inside the room.
        val value = nodes.filter { it.text != null && it.box.left >= room.left - HALF && it.box.right <= room.right + HALF }.maxOf { it.box.width }
        assertEquals("the room is the value, the gap and the glyph", value + RvSpace.trailingGap.value + RvSize.rowGlyph.value, room.width, 1f)
        compose.onNodeWithTag(RvRowPart.TARGET, useUnmergedTree = true).performTouchInput { click(Offset(width - 2f, height / 2f)) }
        assertEquals("a tap near the target's edge opens the node", 1, opened)
    }

    private companion object {
        const val TITLE = "Hong Kong 02 IPLC"
        const val SUBTITLE = "Trojan · 香港"
        const val DETAILS = "詳情"
        const val LATENCY = 48
        const val EDGE = 10
        const val HALF = 0.5f
    }
}
