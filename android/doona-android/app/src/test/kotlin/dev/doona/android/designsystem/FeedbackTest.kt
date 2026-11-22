package dev.doona.android.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.designsystem.components.DoonaEmptyState
import dev.doona.android.designsystem.components.DoonaProgress
import dev.doona.android.designsystem.components.DoonaProgressBar
import dev.doona.android.designsystem.components.DoonaProgressSize
import dev.doona.android.designsystem.components.LayoutRole
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.testing.Dark
import dev.doona.android.testing.Light
import dev.doona.android.testing.Variant
import dev.doona.android.testing.drawFully
import dev.doona.android.testing.layoutTree
import dev.doona.android.testing.seedColors
import dev.doona.android.testing.setVariantContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import dev.doona.android.icons.R as IconsR

/** The progress bar, the empty state and the spinner: what a reader hears, how the bar fills, the empty state's action and inset. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class FeedbackTest {
    @get:Rule
    val compose = createComposeRule()

    private fun hasRange(fraction: Float) = SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(fraction, 0f..1f))

    /** A progress bar is one progress for a screen reader, its fraction held to 0 to 1. */
    @Test
    fun aProgressBarIsOneProgressHeldToItsRange() {
        compose.setVariantContent(Light) {
            Column {
                DoonaProgressBar(0.25f)
                DoonaProgressBar(7f)
                DoonaProgressBar(-1f)
            }
        }
        listOf(0.25f, 1f, 0f).forEach { compose.onAllNodes(hasRange(it)).assertCountEquals(1) }
    }

    @Test
    fun theBarIsFilledByTheFraction() {
        compose.setVariantContent(Light) { Column(Modifier.width(300.dp)) { DoonaProgressBar(0.4f) } }
        val bar = compose.layoutTree().descendants().first { it.role == LayoutRole.Bar }
        val fill = bar.content.single()
        assertEquals("the fill is 40 % of the track", 0.4f, fill.width / bar.width, 0.01f)
        assertEquals("the track is the progress role's height", DoonaTheme.size.progress.value, bar.height, 0.5f)
    }

    private fun contrast(a: Color, b: Color): Float {
        val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (hi + 0.05f) / (lo + 0.05f)
    }

    private fun hex(c: Color) = "#%08X".format(c.toArgb())

    /**
     * What the progress bar draws, read off the rendered pixels: the fill in the accent and the rest of the bar in the
     * track role, the fill 3:1 on its track and on the card, so a palette role that is right on paper but not the one the
     * bar paints cannot pass.
     */
    private fun barDraws(variant: Variant, dark: Boolean) {
        val c = seedColors(dark)
        compose.setVariantContent(variant) { Column(Modifier.width(300.dp).background(c.card)) { DoonaProgressBar(0.5f) } }
        compose.drawFully()
        val bar = compose.layoutTree().descendants().first { it.role == LayoutRole.Bar }
        val density = compose.density.density
        val image = compose.onRoot().captureToImage().toPixelMap()
        fun at(share: Float) = image[((bar.left + bar.width * share) * density).toInt(), (bar.centerY * density).toInt()]
        val fill = at(0.25f)
        val track = at(0.75f)
        assertEquals("the filled half", hex(c.positive), hex(fill))
        assertEquals("the rest of the bar", hex(c.track), hex(track))
        assertTrue("the fill is %.2f:1 on its track".format(contrast(fill, track)), contrast(fill, track) >= 3f)
        assertTrue("the fill is %.2f:1 on the card".format(contrast(fill, c.card)), contrast(fill, c.card) >= 3f)
        assertTrue("the track is %.2f:1 on the card".format(contrast(track, c.card)), contrast(track, c.card) >= TRACK_STEP)
    }

    @Test
    fun theProgressBarDrawsItsFillAndTrackInLight() = barDraws(Light, false)

    @Test
    fun theProgressBarDrawsItsFillAndTrackInDark() = barDraws(Dark, true)

    @Test
    fun theEmptyStateActionTakesATapAndTheTitleIsAHeading() {
        var taps = 0
        compose.setVariantContent(Light) {
            DoonaEmptyState(IconsR.drawable.sym_link, "Nothing here", "Add one.", actionLabel = "Add", onAction = { taps++ })
        }
        compose.onNodeWithText("Add").performClick()
        assertEquals(1, taps)
        compose.onNodeWithText("Nothing here").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).assert(androidx.compose.ui.test.hasText("Add one."))
    }

    @Test
    fun anEmptyStateWithoutAnActionHasNoButton() {
        compose.setVariantContent(Light) {
            Column {
                DoonaEmptyState(IconsR.drawable.sym_search, "No match", "Try another name.")
                DoonaEmptyState(IconsR.drawable.sym_search, "Half", "A label without a tap.", actionLabel = "Add")
                DoonaEmptyState(IconsR.drawable.sym_search, "Other half", "A tap without a label.", onAction = {})
            }
        }
        compose.onAllNodesWithText("Add").assertCountEquals(0)
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.OnClick)).assertCountEquals(0)
    }

    /** The texts keep the inset of a card from the edges of the space they are given, which is not the gutter alone. */
    @Test
    fun theEmptyStateKeepsTheInsetOfACardAtItsSides() {
        compose.setVariantContent(Light) { Column(Modifier.width(300.dp)) { DoonaEmptyState(IconsR.drawable.sym_search, "No match", "Try another name.") } }
        val inset = DoonaTheme.spacing.cardInset.value * compose.density.density
        val text = compose.onNodeWithText("No match").fetchSemanticsNode().boundsInRoot
        assertEquals("the title starts a card inset from the left", inset, text.left, 1f)
        assertEquals("and ends one from the right", 300f * compose.density.density - inset, text.right, 1f)
    }

    /**
     * The spinner is one indeterminate progress in the size of an inline icon or of a touch icon. It sets no description
     * of its own: Android names an indeterminate range "in progress" in the user's language, and a second description
     * would be read beside it.
     */
    @Test
    fun aSpinnerIsAnIndeterminateProgressInTheSizeAsked() {
        compose.setVariantContent(Light) {
            Column {
                DoonaProgress(Modifier.testTag("small"))
                DoonaProgress(Modifier.testTag("inline"), DoonaProgressSize.Inline)
            }
        }
        compose.onNodeWithTag("small").assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription))
            .assertWidthIsEqualTo(DoonaTheme.size.icon).assertHeightIsEqualTo(DoonaTheme.size.icon)
        compose.onNodeWithTag("inline").assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription))
            .assertWidthIsEqualTo(DoonaTheme.size.inlineGlyph).assertHeightIsEqualTo(DoonaTheme.size.inlineGlyph)
    }
}

/** A step one surface up shows on the card: the scheme puts its track one tone step above the card. */
private const val TRACK_STEP = 1.15f
