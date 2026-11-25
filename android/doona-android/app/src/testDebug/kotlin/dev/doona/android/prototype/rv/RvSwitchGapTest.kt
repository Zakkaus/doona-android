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
import dev.doona.android.designsystem.rv.RvRowPart
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.designsystem.rv.RvSwitchRow
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.prototype.rv.settings.RvSettingsSwitchRow
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
 * of the track is read from the capture on the switch's middle line, with the switch on (its thumb at the far end, so
 * the track's start shows). Both the list row ([RvSwitchRow]) and the settings row ([RvSettingsSwitchRow]).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h400dp-notnight-xhdpi")
class RvSwitchGapTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aListSwitchRowsTextStandsTwelveDpFromTheTrack() = gap { RvSwitchRow(R.drawable.rv_sym_rule, TITLE, checked = true, subtitle = SUB) {} }

    @Test
    fun aSettingsSwitchRowsTextStandsTwelveDpFromTheTrack() = gap { RvSettingsSwitchRow(TITLE, R.drawable.rv_sym_rule, true, {}, SUB) }

    private fun gap(row: @Composable () -> Unit) {
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
        val track = image.firstInk(from = text.right.toInt() + 1, to = switch.right.toInt(), y = switch.center.y.toInt())
        assertTrue("no pixel of the track on the switch's middle line between the text and the switch's end", track != null)
        assertEquals("the track starts where the switch's node does", switch.left / density, track!! / density, HALF)
        assertEquals("R17: text end to the drawn track", RvSpace.trailingGap.value, (track - text.right) / density, HALF)
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

    private companion object {
        const val TITLE = "在每一個網路上自動連線並套用目前的設定"
        const val SUB = "包括行動網路與新加入的 Wi-Fi"
        const val HALF = 0.5f
        const val INK = 0.08f
    }
}
