package dev.doona.android.designsystem

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import dev.doona.android.testing.FontScale115
import dev.doona.android.testing.FontScale200
import dev.doona.android.testing.GalleryVariants
import dev.doona.android.testing.Variant
import dev.doona.android.testing.galleryCases
import dev.doona.android.testing.screenCases
import dev.doona.android.testing.setVariantContent
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * No component clips text silently. Text that does not fit either wraps, so it shows whole, or ends in an ellipsis, so
 * the reader sees that something is missing; a value cut at the edge of its box with no mark is a defect at any font
 * size, and a larger font is where it shows.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class TextOverflowTest(private val name: String, private val variant: Variant) {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun textThatDoesNotFitWrapsOrEllipsizes() {
        val case = (screenCases() + galleryCases()).first { it.name == name }
        compose.setVariantContent(variant) { case.content() }
        compose.waitForIdle()
        val clipped = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true).fetchSemanticsNodes().mapNotNull { node ->
            val results = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
            results.firstOrNull()?.takeIf { it.layoutInput.overflow != TextOverflow.Ellipsis && it.cutOff() }?.layoutInput?.text?.text
        }
        assertTrue("$name at font scale ${variant.fontScale} clips text with no ellipsis: $clipped", clipped.isEmpty())
    }

    /** Whether the layout draws less than the text says: a line runs past the box's edge, or lines are dropped or lie below it. */
    private fun TextLayoutResult.cutOff(): Boolean {
        val last = lineCount - 1
        return multiParagraph.didExceedMaxLines || getLineBottom(last) > size.height + SLACK || (0..last).any { getLineRight(it) > size.width + SLACK }
    }

    companion object {
        private const val SLACK = 1f

        @JvmStatic
        @Parameters(name = "{0}-{1}")
        fun parameters(): List<Array<Any>> =
            screenCases().flatMap { case -> listOf(FontScale115, FontScale200).map { arrayOf(case.name, it) } } +
                galleryCases().flatMap { case -> GalleryVariants.filter { it.fontScale > 1f }.map { arrayOf(case.name, it) } }
    }
}
