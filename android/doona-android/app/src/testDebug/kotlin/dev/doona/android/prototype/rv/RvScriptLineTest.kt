package dev.doona.android.prototype.rv

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RvIconDisc
import dev.doona.android.designsystem.rv.RvRow
import dev.doona.android.designsystem.rv.RvSettingsRow
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
 * A row's title and subtitle take their role's Latin line box whatever their script
 * ([dev.doona.android.designsystem.rv.RvLineBox]): Inter and Noto differ in ascent and descent, and a line mixing them
 * came out 1 px taller than a line of either, so a row of Latin alone (設定's GeoIP/GeoSite over its date) set its text,
 * disc and button 0.5 dp lower than a row of mixed text. Text outside rows keeps its own line boxes.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvScriptLineTest {
    @get:Rule
    val compose = createComposeRule()

    /** A list row's and a settings row's title, subtitle and height, in Latin, CJK and mixed text, and a wrapped subtitle. */
    @Test
    fun aRowIsAsTallInEveryScript() {
        compose.rvContent(dark = false) {
            Column {
                SCRIPTS.forEachIndexed { i, (title, subtitle) ->
                    RvRow({ RvIconDisc(R.drawable.rv_sym_settings) }, title, subtitle, Modifier.testTag("list$i"))
                    RvSettingsRow(title, Modifier.testTag("settings$i"), icon = R.drawable.rv_sym_settings, subtitle = subtitle)
                }
                WRAPPED.forEachIndexed { i, it -> Box(Modifier.width(WRAP_WIDTH)) { RvRow({}, TITLE_OF_WRAPPED[i], it, Modifier.testTag("wrap$i")) } }
            }
        }
        SCRIPTS.forEachIndexed { i, (title, subtitle) ->
            listOf("list", "settings").forEach { row ->
                assertEquals("$title as a $row row's title, as ${SCRIPTS[0].first}", text("${row}0", SCRIPTS[0].first).size.height, text("$row$i", title).size.height)
                assertEquals("$subtitle as a $row row's subtitle", text("${row}0", SCRIPTS[0].second).size.height, text("$row$i", subtitle).size.height)
            }
            assertEquals("the list row of $title", tagged("list0").size.height, tagged("list$i").size.height)
            assertEquals("the settings row of $title", tagged("settings0").size.height, tagged("settings$i").size.height)
        }
        assertEquals("three lines of Latin, and of mixed text", text("wrap0", WRAPPED[0]).size.height, text("wrap1", WRAPPED[1]).size.height)
    }

    /** 設定's GeoIP/GeoSite (Latin alone) and 關於 doona (mixed) over a version: their text one distance under the row's top, in both rows. */
    @Test
    fun aRowOfLatinSetsItsTextAsOneOfMixedText() {
        val rows = listOf("GeoIP/GeoSite" to "2026/11/20", "關於 doona" to "0.1.0")
        compose.rvContent(dark = false) {
            Column {
                rows.forEachIndexed { i, (title, subtitle) ->
                    RvSettingsRow(title, Modifier.testTag("settings$i"), icon = R.drawable.rv_sym_settings, subtitle = subtitle)
                    RvRow({ RvIconDisc(R.drawable.rv_sym_settings) }, title, subtitle, Modifier.testTag("list$i"))
                }
            }
        }
        listOf("settings", "list").forEach { row ->
            val drops = rows.mapIndexed { i, (title, _) -> text("$row$i", title).positionInRoot.y - tagged("$row$i").positionInRoot.y }
            assertEquals("a $row row's title, from its row's top", drops[1], drops[0], 0.01f)
        }
    }

    /**
     * A mixed title (`關於 doona`) stands on the baseline its Latin alone (`doona`) does in the same row: the last
     * letter's ink ends on one px row in both, in a list row and in a settings row.
     */
    @Test
    fun aMixedTitleStandsOnTheLatinBaseline() {
        val titles = listOf("doona", "關於 doona")
        compose.rvContent(dark = false) {
            Column(Modifier.padding(top = CLEAR_OF_BARS)) {
                titles.forEachIndexed { i, title ->
                    RvRow({}, title, null, Modifier.testTag("list$i"))
                    RvSettingsRow(title, Modifier.testTag("settings$i"))
                }
            }
        }
        listOf("list", "settings").forEach { row ->
            assertEquals("the foot of a $row row's last letter, Latin and mixed", lastLetterFoot("${row}0"), lastLetterFoot("${row}1"))
        }
    }

    /**
     * At 2.0 a title of two lines that mixes Han and Latin, descenders on its last line, keeps its ink inside its node,
     * its line boxes: the boxes give up the px Noto's ascent adds at the top, above any glyph.
     */
    @Test
    fun aMixedTitleOfTwoLinesIsNotCutAtTwo() {
        compose.rvContent(dark = false, fontScale = RV_FS200) {
            // Clear of the frame's status bar, which would draw in the rows' images.
            Column(Modifier.padding(top = CLEAR_OF_BARS)) {
                RvRow({}, LONG_MIXED, null, Modifier.testTag("list"))
                RvSettingsRow(LONG_MIXED, Modifier.testTag("settings"))
            }
        }
        listOf("list", "settings").forEach { tag ->
            val text = text(tag, LONG_MIXED)
            val laid = layout(text)
            assertEquals("$tag: $LONG_MIXED takes two lines", 2, laid.lineCount)
            assertTrue("$tag: the boxes, ${text.size.height} px, take px from the laid out ${laid.size.height}", text.size.height < laid.size.height)
            val row = tagged(tag)
            val ink = inkRows(tag)
            val top = text.positionInRoot.y - row.positionInRoot.y
            assertTrue("$tag: ink from ${ink.first} under the title's top $top", ink.first >= top)
            assertTrue("$tag: ink to ${ink.last} over the title's bottom ${top + text.size.height}", ink.last < top + text.size.height)
        }
    }

    /** The first and last px row of [tag]'s image that differ from its corner, the row's ground. */
    private fun inkRows(tag: String): IntRange {
        val pixels = compose.onNodeWithTag(tag).captureToImage().toPixelMap()
        val ground = pixels[0, 0]
        val inked = (0 until pixels.height).filter { y -> (0 until pixels.width).any { x -> differs(pixels[x, y], ground) } }
        return inked.first()..inked.last()
    }

    /** The lowest inked px row under the last [LAST_LETTER] px columns of ink in [tag]'s image: its last letter's foot. */
    private fun lastLetterFoot(tag: String): Int {
        val pixels = compose.onNodeWithTag(tag).captureToImage().toPixelMap()
        val ground = pixels[0, 0]
        val inked = { x: Int, y: Int -> differs(pixels[x, y], ground) }
        val end = (0 until pixels.width).last { x -> (0 until pixels.height).any { y -> inked(x, y) } }
        return (0 until pixels.height).last { y -> (end - LAST_LETTER..end).any { x -> inked(x, y) } }
    }

    private fun differs(a: Color, b: Color) = abs(a.red - b.red) + abs(a.green - b.green) + abs(a.blue - b.blue) > INK

    private fun tagged(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()

    /** The node of [text] in the row tagged [tag]. */
    private fun text(tag: String, text: String) = compose.onNode(hasText(text) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true).fetchSemanticsNode()

    private fun layout(node: SemanticsNode): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        return layouts.single()
    }

    private companion object {
        /** A title over a subtitle: Latin alone, CJK alone, mixed. */
        val SCRIPTS = listOf("GeoIP/GeoSite" to "2026/11/20", "檢查更新" to "自動檢查", "關於 doona" to "每 12 小時")
        val TITLE_OF_WRAPPED = listOf("Latin", "Mixed")
        val WRAPPED = listOf("GeoIP GeoSite update check every twelve hours", "只有選取的 App 經由代理，其他 App 與區域網路位址直接連線")
        val WRAP_WIDTH = 200.dp
        val CLEAR_OF_BARS = 160.dp

        /** Han and Latin with descenders, two lines of a row's title at 2.0. */
        const val LONG_MIXED = "香港 gateway 節點 jumpy query"

        /** How many px columns of the last letter ([lastLetterFoot]): within an `a`, clear of the letter before it. */
        const val LAST_LETTER = 4

        /** How far a px may differ from the ground, summed over its channels, and still be ground. */
        const val INK = 0.1f
    }
}
