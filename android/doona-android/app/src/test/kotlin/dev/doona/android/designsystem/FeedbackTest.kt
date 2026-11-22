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
import dev.doona.android.designsystem.components.DoonaMeter
import dev.doona.android.designsystem.components.DoonaProgress
import dev.doona.android.designsystem.components.DoonaProgressSize
import dev.doona.android.designsystem.components.LayoutRole
import dev.doona.android.designsystem.theme.DoonaColors
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.designsystem.tokens.Dawn
import dev.doona.android.designsystem.tokens.DoonaPalette
import dev.doona.android.designsystem.tokens.Moon
import dev.doona.android.testing.Dark
import dev.doona.android.testing.Light
import dev.doona.android.testing.Variant
import dev.doona.android.testing.drawFully
import dev.doona.android.testing.layoutTree
import dev.doona.android.testing.setVariantContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import dev.doona.android.icons.R as IconsR

/** The meter, the empty state and the spinner: what a reader hears, the room between the meter's text and its bar, the empty state's action and inset. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class FeedbackTest {
    @get:Rule
    val compose = createComposeRule()

    private fun hasRange(fraction: Float) = SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(fraction, 0f..1f))

    /** The value text is read once: as the merged text of the progress, with no state description saying it again beside it. */
    @Test
    fun aMeterIsOneProgressAndItsValueTextIsReadOnce() {
        compose.setVariantContent(Light) {
            Column {
                DoonaMeter(0.25f, "25 / 100 GB")
                DoonaMeter(7f, "over")
                DoonaMeter(-1f, "under")
            }
        }
        compose.onNodeWithText("25 / 100 GB").assert(hasRange(0.25f)).assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
        assertEquals(listOf("25 / 100 GB"), compose.onNodeWithText("25 / 100 GB").fetchSemanticsNode().config[SemanticsProperties.Text].map { it.text })
        compose.onNodeWithText("over").assert(hasRange(1f))
        compose.onNodeWithText("under").assert(hasRange(0f))
    }

    @Test
    fun theBarIsFilledByTheFraction() {
        compose.setVariantContent(Light) { Column(Modifier.width(300.dp)) { DoonaMeter(0.4f, "40 %") } }
        val bar = compose.layoutTree().descendants().first { it.role == LayoutRole.Bar }
        val fill = bar.content.single()
        assertEquals("the fill is 40 % of the track", 0.4f, fill.width / bar.width, 0.01f)
        assertEquals("the track is 4 dp", DoonaTheme.size.meterTrack.value, bar.height, 0.5f)
    }

    private fun contrast(a: Color, b: Color): Float {
        val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (hi + 0.05f) / (lo + 0.05f)
    }

    private fun hex(c: Color) = "#%08X".format(c.toArgb())

    /**
     * What the meter draws, read off the rendered pixels: the fill in the meter fill role and the rest of the bar in the
     * track role, the fill 3:1 on its track and on the card, so a palette role that is right on paper but not the one the
     * meter paints cannot pass.
     */
    private fun meterDraws(variant: Variant, palette: DoonaPalette) {
        val c = DoonaColors(palette)
        compose.setVariantContent(variant) { Column(Modifier.width(300.dp).background(c.card)) { DoonaMeter(0.5f, "50 %") } }
        compose.drawFully()
        val bar = compose.layoutTree().descendants().first { it.role == LayoutRole.Bar }
        val density = compose.density.density
        val image = compose.onRoot().captureToImage().toPixelMap()
        fun at(share: Float) = image[((bar.left + bar.width * share) * density).toInt(), (bar.centerY * density).toInt()]
        val fill = at(0.25f)
        val track = at(0.75f)
        assertEquals("the filled half", hex(c.meterFill), hex(fill))
        assertEquals("the rest of the bar", hex(c.track), hex(track))
        assertTrue("the fill is %.2f:1 on its track".format(contrast(fill, track)), contrast(fill, track) >= 3f)
        assertTrue("the fill is %.2f:1 on the card".format(contrast(fill, c.card)), contrast(fill, c.card) >= 3f)
        assertTrue("the track is %.2f:1 on the card".format(contrast(track, c.card)), contrast(track, c.card) >= 1.4f)
    }

    @Test
    fun theMeterDrawsItsFillAndTrackInLight() = meterDraws(Light, Dawn)

    @Test
    fun theMeterDrawsItsFillAndTrackInDark() = meterDraws(Dark, Moon)

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
            .assertWidthIsEqualTo(DoonaTheme.size.iconInline).assertHeightIsEqualTo(DoonaTheme.size.iconInline)
    }
}
