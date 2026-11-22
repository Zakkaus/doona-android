package dev.doona.android.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.designsystem.components.DoonaCheckRow
import dev.doona.android.designsystem.theme.DoonaColors
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.designsystem.tokens.Dawn
import dev.doona.android.designsystem.tokens.TypeScale
import dev.doona.android.testing.SpacingRules
import dev.doona.android.testing.layoutTree
import dev.doona.android.testing.setDoonaContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** What a check row does: the whole row toggles, a reader hears its state, it keeps the row rules, and the box shows the state. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xxhdpi")
class CheckRowTest {
    @get:Rule
    val compose = createComposeRule()

    private fun hasState(state: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, state)

    @Test
    fun tappingTheTitleTogglesTheRow() {
        val changes = mutableListOf<Boolean>()
        compose.setDoonaContent { DoonaCheckRow(checked = false, onChange = { changes += it }, title = "Chrome", subtitle = "com.android.chrome") }
        compose.onNodeWithText("Chrome").performClick()
        compose.onNodeWithText("com.android.chrome").performClick()
        assertEquals(listOf(true, true), changes)
    }

    @Test
    fun aReaderHearsTheTitleTheSubtitleAndTheState() {
        compose.setDoonaContent {
            Column {
                DoonaCheckRow(checked = true, onChange = {}, title = "On row", subtitle = "sub")
                DoonaCheckRow(checked = false, onChange = {}, title = "Off row")
            }
        }
        compose.onNodeWithText("On row").assertIsOn().assert(hasState("開啟")).assert(androidx.compose.ui.test.hasText("sub"))
        compose.onNodeWithText("Off row").assertIsOff().assert(hasState("關閉"))
    }

    @Test
    fun aRowIsFortyEightHighWithOrWithoutASubtitleAndAnIcon() {
        compose.setDoonaContent {
            Column {
                DoonaCheckRow(checked = true, onChange = {}, title = "Plain")
                DoonaCheckRow(checked = true, onChange = {}, title = "Two lines", subtitle = "sub", leading = { Box(Modifier.size(40.dp).background(Color.Gray)) })
            }
        }
        compose.onNodeWithText("Plain").assertHeightIsEqualTo(48.dp)
        compose.onNodeWithText("Two lines").assertHeightIsEqualTo(48.dp)
    }

    /** The row's own rules: it is 48 high, pads nothing at its edges, an icon is a block from its text, nothing hangs out, and the touch area is whole. */
    @Test
    fun aRowKeepsTheRowRules() {
        compose.setDoonaContent {
            Column {
                DoonaCheckRow(checked = true, onChange = {}, title = "Chrome", subtitle = "com.android.chrome", leading = { Box(Modifier.size(40.dp).background(Color.Gray)) })
                DoonaCheckRow(checked = false, onChange = {}, title = "Telegram")
            }
        }
        val tree = compose.layoutTree()
        assertEquals(emptyList<String>(), SpacingRules.rows(tree))
        assertEquals(emptyList<String>(), SpacingRules.contained(tree))
        assertEquals(emptyList<String>(), SpacingRules.touch(tree).violations)
    }

    private fun lines(text: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.single()
    }

    /**
     * A title too long for one line takes a second, which an application's name needs on a narrow row, and the two lines
     * fit the row's 48 dp; a title that needs a third ends in an ellipsis on the second line.
     */
    @Test
    fun aLongTitleWrapsOntoTwoLinesAndOnlyAThirdIsCutOff() {
        val two = "A long application name that cannot fit on one line of the row"
        val many = "A long application name that cannot fit on one line of the row, nor on two, because it goes on and on about what the application does and who made it"
        compose.setDoonaContent {
            Column(Modifier.width(260.dp).background(Color.Gray)) {
                DoonaCheckRow(checked = false, onChange = {}, title = "Short")
                DoonaCheckRow(checked = false, onChange = {}, title = two)
                DoonaCheckRow(checked = false, onChange = {}, title = many)
            }
        }
        assertEquals(1, lines("Short").lineCount)
        assertEquals("a title that wraps takes two lines", 2, lines(two).lineCount)
        assertFalse("and none of it is cut", (0 until 2).any { lines(two).isLineEllipsized(it) })
        assertEquals("a title that needs a third is held to two", 2, lines(many).lineCount)
        assertTrue("and ends in an ellipsis", lines(many).isLineEllipsized(1))
        val line = TypeScale.label.line.value
        assertEquals("the two lines are drawn whole, one under the other", 2 * line, lines(two).size.height / compose.density.density, 1f)
        compose.onNodeWithText(two).assertHeightIsEqualTo(48.dp)
        assertEquals(emptyList<String>(), SpacingRules.rows(compose.layoutTree()))
    }

    private fun hex(c: Color) = "#%08X".format(c.toArgb())

    /** Inside the box, away from its checkmark and its outline: the accent when checked and the surface when not. */
    @Test
    fun theBoxIsFilledWhenCheckedAndOutlinedWhenNot() {
        val c = DoonaColors(Dawn)
        compose.setDoonaContent {
            Column(Modifier.fillMaxSize().background(DoonaTheme.colors.surface)) {
                DoonaCheckRow(checked = true, onChange = {}, title = "Checked")
                DoonaCheckRow(checked = false, onChange = {}, title = "Unchecked")
            }
        }
        val density = compose.density.density
        val image = compose.onRoot().captureToImage().toPixelMap()
        fun boxPixel(title: String, fromEnd: Float, fromCentre: Float): Color {
            val row = compose.onNodeWithText(title).fetchSemanticsNode().boundsInRoot
            return image[(row.right - fromEnd * density).toInt(), (row.center.y + fromCentre * density).toInt()]
        }
        assertEquals(hex(c.accent), hex(boxPixel("Checked", fromEnd = 3f, fromCentre = 6f)))
        assertEquals(hex(c.surface), hex(boxPixel("Unchecked", fromEnd = 3f, fromCentre = 6f)))
        assertEquals("the outline of the unchecked box", hex(c.boxLine), hex(boxPixel("Unchecked", fromEnd = 1f, fromCentre = 0f)))
    }
}
