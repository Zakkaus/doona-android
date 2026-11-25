package dev.doona.android.prototype.rv

import android.content.Context
import android.content.res.Configuration
import android.icu.text.BreakIterator
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ApplicationProvider
import dev.doona.android.R
import dev.doona.android.designsystem.rv.Rv
import dev.doona.android.designsystem.rv.RvText
import dev.doona.android.designsystem.rv.rvKeepWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

/**
 * A wrapped [RvText] breaks Chinese between its words, as ICU's dictionary finds them, where the platform breaks between
 * any two characters (「網|站」); a word longer than its line still breaks inside; a name kept as the platform breaks it
 * (`keepWords = false`) is left as it is; and the text's node reads the plain words, without the word joiners.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvTextWordsTest {
    @get:Rule
    val compose = createComposeRule()

    private var width by mutableIntStateOf(WIDTHS.first())
    private var text by mutableStateOf("")
    private var keepWords by mutableStateOf(true)

    @Test
    fun aWrappedSentenceAtTwiceTheFontSizeBreaksBetweenWords() {
        show(fontScale = 2f)
        val broken = SENTENCES.flatMap { sentence ->
            text = sentence
            WIDTHS.flatMap { room ->
                width = room
                compose.waitForIdle()
                brokenWords(layout()).map { "$it at $room px" }
            }
        }
        assertTrue("words broken inside: $broken", broken.isEmpty())
    }

    @Test
    fun aWordLongerThanItsLineStillBreaksInside() {
        val word = LONG_WORDS.first { rvKeepWords(it).count { c -> c == JOINER } == it.length - 1 }
        show(fontScale = 2f)
        text = word
        width = NARROW
        compose.waitForIdle()
        val laid = layout()
        assertTrue("$word on ${laid.lineCount} lines at $NARROW px", laid.lineCount > 1)
        assertFalse("$word cut", laid.hasVisualOverflow || (0 until laid.lineCount).any(laid::isLineEllipsized))
    }

    @Test
    fun aNameKeptAsThePlatformBreaksItIsNotJoined() {
        // At 1× the name takes one line, so no wrap step joins its last word either.
        show(fontScale = 1f)
        text = NODE_NAME
        width = WIDTHS.last()
        keepWords = false
        compose.waitForIdle()
        assertFalse("a node's name joined", JOINER in layout().layoutInput.text.text)
        keepWords = true
        compose.waitForIdle()
        assertTrue("app text not joined", JOINER in layout().layoutInput.text.text)
    }

    @Test
    fun theNodeReadsThePlainWords() {
        show(fontScale = 2f)
        text = SENTENCES.first()
        width = WIDTHS.first()
        compose.waitForIdle()
        assertTrue("the layout holds word joiners", JOINER in layout().layoutInput.text.text)
        val read = compose.onNodeWithTag(TAG).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }
        assertEquals(text, read)
        compose.onNodeWithText(text).assertExists()
    }

    private fun show(fontScale: Float) {
        compose.rvContent(dark = false, fontScale = fontScale) {
            val room = with(LocalDensity.current) { width.toDp() }
            Box(Modifier.width(room)) { RvText(text, Rv.type.rowSub, Modifier.testTag(TAG), maxLines = Int.MAX_VALUE, keepWords = keepWords) }
        }
    }

    private fun layout(): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(TAG).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        return layouts.single()
    }

    /** Each line of [layout] that ends inside an ICU word of the plain text, as `前|後`. */
    private fun brokenWords(layout: TextLayoutResult): List<String> {
        val shown = layout.layoutInput.text.text
        val plain = shown.filterNot { it in INVISIBLE }
        val words = BreakIterator.getWordInstance(Locale.CHINESE).apply { setText(plain) }
        return (0 until layout.lineCount - 1).mapNotNull { line ->
            val at = shown.substring(0, layout.getLineEnd(line)).count { it !in INVISIBLE }
            if (words.isBoundary(at)) null else plain.substring((at - 2).coerceAtLeast(0), at) + "|" + plain.substring(at, (at + 2).coerceAtMost(plain.length))
        }
    }

    private companion object {
        const val TAG = "words"
        const val JOINER = '⁠'
        val INVISIBLE = setOf(JOINER, '​')

        /** Rooms a footnote, a banner's reason and a row's subtitle take at 2× on the 412 dp phone, in px. */
        val WIDTHS = (560..760 step 40).toList()

        /** Three letters at 2×: narrower than a long word. */
        const val NARROW = 220

        /** Words ICU's dictionary keeps whole, longest first; the test takes the first it keeps whole. */
        val LONG_WORDS = listOf("中華人民共和國", "國際標準化組織", "中國大陸", "網際網路")

        /** A node's name, as a subscription names it. */
        const val NODE_NAME = "香港中國大陸網站直接連線節點"

        /** App sentences the panels found broken inside a word at 1.15× or 2× (in zh-TW and zh-CN). */
        val SENTENCES: List<String> by lazy {
            val app = ApplicationProvider.getApplicationContext<Context>()
            val cn = app.createConfigurationContext(Configuration(app.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) })
            listOf(
                app.getString(R.string.rv_offline_reason),
                app.getString(R.string.rv_redact_footer),
                app.getString(R.string.rv_template_homebound_line),
                cn.getString(R.string.rv_license_brand_marks_text).substringBefore('\n'),
            )
        }
    }
}
