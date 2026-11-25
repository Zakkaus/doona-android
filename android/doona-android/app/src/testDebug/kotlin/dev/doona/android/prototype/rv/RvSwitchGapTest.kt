package dev.doona.android.prototype.rv

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RvCard
import dev.doona.android.designsystem.rv.RvControlSize
import dev.doona.android.designsystem.rv.RvRowPart
import dev.doona.android.designsystem.rv.RvSettingsSwitchRow
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.designsystem.rv.RvSwitchRow
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.testing.drawFully
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * A switch row's text stands 12 dp from the switch's drawn track (LAYOUT-RULES.md R17, owner q-s1125c-switchgap): the
 * title's box ends where the text column does (the title wraps, so its box is the column's width) and the first pixel
 * of ink is read from the capture on the switch's middle line. On, the thumb is at the far end, so the track's start
 * shows: 12 dp, a measured figure, not the token. Off, the 19 dp thumb overhangs the 14 dp track's start by 2.5 dp, so
 * the nearest ink is 9.5 dp from the text, the value the owner kept (q-l4-thumb, 2026-11-25 23:52: 12 dp to the track,
 * the off thumb at 9.5 dp); the thumb hides the track's start, so off, the track is read from its far end, which shows,
 * less its width: 12 dp as well (R17). Both the list row ([RvSwitchRow]) and the settings row ([RvSettingsSwitchRow]).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h400dp-notnight-xhdpi")
class RvSwitchGapTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aListSwitchRowsTextStandsTwelveDpFromTheTrack() = gap(TO_TRACK, onTrack = true) { RvSwitchRow(R.drawable.rv_sym_rule, TITLE, checked = true, subtitle = SUB) {} }

    @Test
    fun aSettingsSwitchRowsTextStandsTwelveDpFromTheTrack() = gap(TO_TRACK, onTrack = true) { RvSettingsSwitchRow(TITLE, R.drawable.rv_sym_rule, true, {}, SUB) }

    @Test
    fun anOffListSwitchsThumbStandsNineAndAHalfDpFromTheText() = gap(TO_THUMB_OFF, onTrack = false) { RvSwitchRow(R.drawable.rv_sym_rule, TITLE, checked = false, subtitle = SUB) {} }

    @Test
    fun anOffSettingsSwitchsThumbStandsNineAndAHalfDpFromTheText() = gap(TO_THUMB_OFF, onTrack = false) { RvSettingsSwitchRow(TITLE, R.drawable.rv_sym_rule, false, {}, SUB) }

    private fun gap(want: Float, onTrack: Boolean, row: @Composable () -> Unit) {
        compose.setContent {
            RvTheme(seedColorScheme(Seed.Cyan.argb, false), false) {
                Column(Modifier.padding(horizontal = RvSpace.gutter)) { RvCard { row() } }
            }
        }
        compose.drawFully()
        val density = compose.density.density
        val text = compose.onNodeWithText(TITLE, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val switch = compose.onNodeWithTag(RvRowPart.SWITCH, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val image = compose.onRoot().captureToImage()
        val ink = image.firstInk(from = text.right.toInt() + 1, to = switch.right.toInt(), y = switch.center.y.toInt())
        assertTrue("no ink on the switch's middle line between the text and the switch's end", ink != null)
        assertEquals("R17: text end to the nearest ink of the switch", want, (ink!! - text.right) / density, HALF)
        if (onTrack) {
            assertEquals("the track starts where the switch's node does", switch.left / density, ink / density, HALF)
        } else {
            val end = image.lastInk(from = text.right.toInt() + 1, to = switch.right.toInt(), y = switch.center.y.toInt())
            assertTrue("no track on the switch's middle line", end != null)
            val track = end!! + 1 - RvControlSize.switchTrackWidth.value * density
            assertEquals("R17 (q-l4-thumb): off, text end to the track (its far end less its width)", TO_TRACK, (track - text.right) / density, HALF)
        }
    }

    /** The first x in [from, to) on line [y] whose pixel differs clearly from the card's colour at [from]. */
    private fun ImageBitmap.firstInk(from: Int, to: Int, y: Int): Int? {
        val pixels = toPixelMap()
        val card = pixels[from, y]
        return (from until to).firstOrNull { x ->
            val c = pixels[x, y]
            (abs(c.red - card.red) + abs(c.green - card.green) + abs(c.blue - card.blue)) / 3 > INK
        }
    }

    /** The last x in [from, to) on line [y] whose pixel differs clearly from the card's colour at [from]. */
    private fun ImageBitmap.lastInk(from: Int, to: Int, y: Int): Int? {
        val pixels = toPixelMap()
        val card = pixels[from, y]
        return (from until to).lastOrNull { x ->
            val c = pixels[x, y]
            (abs(c.red - card.red) + abs(c.green - card.green) + abs(c.blue - card.blue)) / 3 > INK
        }
    }

    private companion object {
        const val TITLE = "在每一個網路上自動連線並套用目前的設定"
        const val SUB = "包括行動網路與新加入的 Wi-Fi"
        const val HALF = 0.5f
        const val INK = 0.08f

        /** The owner's 12 dp (q-s1125c-switchgap), text to the drawn track. */
        const val TO_TRACK = 12f

        /** Off: 12 dp to the track less the thumb's 2.5 dp overhang ((19 - 14) / 2), as the owner kept it (q-l4-thumb). */
        const val TO_THUMB_OFF = 9.5f
    }
}
