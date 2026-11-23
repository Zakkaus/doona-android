package dev.doona.android.designsystem

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.designsystem.components.DoonaLatency
import dev.doona.android.designsystem.components.DoonaListRow
import dev.doona.android.designsystem.components.DoonaMetricLatency
import dev.doona.android.designsystem.components.DoonaPageTabs
import dev.doona.android.designsystem.components.DoonaTag
import dev.doona.android.designsystem.components.DoonaText
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.testing.setDoonaContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** What the components say to a screen reader: state that colour alone would carry, merged rows, and static versions. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xxhdpi")
class ComponentSemanticsTest {
    @get:Rule
    val compose = createComposeRule()

    private fun hasState(state: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, state)

    @Test
    fun aLatencyNamesItsQualityInEveryTone() {
        compose.setDoonaContent {
            DoonaLatency(42)
            DoonaLatency(171)
            DoonaLatency(480)
            DoonaLatency(null)
            DoonaMetricLatency(42)
        }
        mapOf("良好" to 2, "普通" to 1, "緩慢" to 1, "未測試" to 1).forEach { (state, count) -> compose.onAllNodes(hasState(state)).assertCountEquals(count) }
    }

    @Test
    fun aRowReadsAsOneLine() {
        compose.setDoonaContent {
            DoonaListRow("Label", subtitle = "Sub", trailing = { DoonaText("Value", DoonaTheme.type.body) })
        }
        compose.onNode(hasText("Label") and hasText("Sub") and hasText("Value")).assertExists()
    }

    @Test
    fun staticTabsShowTheCurrentPageAndOfferNoAction() {
        compose.setDoonaContent { DoonaPageTabs(listOf("One", "Two"), selected = 0) }
        compose.onAllNodes(isSelectable()).assertCountEquals(2)
        compose.onNodeWithText("One").assertIsSelected().assertHasNoClickAction()
        compose.onNodeWithText("Two").assertHasNoClickAction()
    }

    @Test
    fun aTagWithoutAHandlerIsALabel() {
        compose.setDoonaContent { DoonaTag("Auto") }
        compose.onNodeWithText("Auto").assertHasNoClickAction()
    }
}
