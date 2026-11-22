package dev.doona.android.designsystem

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.R
import dev.doona.android.designsystem.components.DoonaEmptyAction
import dev.doona.android.designsystem.components.DoonaEmptyState
import dev.doona.android.designsystem.components.DoonaMeter
import dev.doona.android.designsystem.components.LayoutRole
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.testing.Light
import dev.doona.android.testing.layoutTree
import dev.doona.android.testing.setVariantContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The meter and the empty state: what a reader hears, the room between the meter's text and its bar, and the empty state's action. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class FeedbackTest {
    @get:Rule
    val compose = createComposeRule()

    private fun hasRange(fraction: Float) = SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(fraction, 0f..1f))

    @Test
    fun aMeterIsOneProgressWithItsValueTextAsItsState() {
        compose.setVariantContent(Light) {
            Column {
                DoonaMeter(0.25f, "25 / 100 GB")
                DoonaMeter(7f, "over")
                DoonaMeter(-1f, "under")
            }
        }
        compose.onNodeWithText("25 / 100 GB").assert(hasRange(0.25f))
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

    @Test
    fun theEmptyStateActionTakesATapAndTheTitleIsAHeading() {
        var taps = 0
        compose.setVariantContent(Light) {
            DoonaEmptyState(R.drawable.ic_link, "Nothing here", "Add one.", action = DoonaEmptyAction("Add", onClick = { taps++ }))
        }
        compose.onNodeWithText("Add").performClick()
        assertEquals(1, taps)
        compose.onNodeWithText("Nothing here").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).assert(androidx.compose.ui.test.hasText("Add one."))
    }

    @Test
    fun anEmptyStateWithoutAnActionHasNoButton() {
        compose.setVariantContent(Light) { DoonaEmptyState(R.drawable.ic_search, "No match", "Try another name.") }
        compose.onAllNodesWithText("Add").assertCountEquals(0)
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.OnClick)).assertCountEquals(0)
    }
}
