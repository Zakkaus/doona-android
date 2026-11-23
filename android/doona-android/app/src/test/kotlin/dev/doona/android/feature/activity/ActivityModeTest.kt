package dev.doona.android.feature.activity

import android.content.Context
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.R
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.engine.Mode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Until the 策略 page offers Rule, 活動 shows it as Global; that display must never be written back as the mode. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-notnight-xxhdpi")
class ActivityModeTest {
    @get:Rule
    val compose = createComposeRule()

    private val actions = mutableListOf<ActivityAction>()

    private fun segment(label: Int) =
        compose.onNode(isSelectable() and hasText(ApplicationProvider.getApplicationContext<Context>().getString(label)))

    @Test
    fun inRuleATapOnGlobalWhichLooksSelectedKeepsRule() {
        compose.setContent {
            DoonaTheme { ActivityScreen(ActivityUiState(mode = Mode.Rule), onAction = { actions += it }, onConnect = {}, onOpenGroup = {}) }
        }
        segment(R.string.mode_global).assertIsSelected().performClick()
        compose.waitForIdle()
        assertEquals(emptyList<ActivityAction>(), actions)
        segment(R.string.mode_direct).performClick()
        compose.waitForIdle()
        assertEquals(listOf<ActivityAction>(ActivityAction.SetMode(Mode.Direct)), actions)
    }
}
