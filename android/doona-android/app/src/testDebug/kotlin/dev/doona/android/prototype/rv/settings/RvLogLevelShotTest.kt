package dev.doona.android.prototype.rv.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.feature.settings.RV_DEBUG_HOURS
import dev.doona.android.feature.settings.RvLogLevel
import dev.doona.android.feature.settings.RvLogLevelScreen
import dev.doona.android.prototype.rv.RV_FS115
import dev.doona.android.prototype.rv.RV_FS200
import dev.doona.android.prototype.rv.Seed
import dev.doona.android.prototype.rv.rvShot
import dev.doona.android.prototype.rv.rvText
import dev.doona.android.prototype.rv.seedColorScheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 日誌等級 at its default 資訊 (`proto-rv-settings-log-level-*`) and with 除錯 on (`-log-level-debug-*`, the name the
 * countdown sample had, so the sheet compares the two), each light, dark, fs115 and fs200; then that a row chooses its
 * level and that 除錯 adds no line to its row, the footer alone telling the hour.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvLogLevelShotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test fun light() = compose.rvShot("settings-log-level", dark = false) { Page(RvLogLevel.Info) }

    @Test fun dark() = compose.rvShot("settings-log-level", dark = true) { Page(RvLogLevel.Info) }

    @Test fun fs115() = compose.rvShot("settings-log-level", dark = false, fontScale = RV_FS115) { Page(RvLogLevel.Info) }

    @Test fun fs200() = compose.rvShot("settings-log-level", dark = false, fontScale = RV_FS200) { Page(RvLogLevel.Info) }

    @Test fun debugLight() = compose.rvShot("settings-log-level-debug", dark = false) { Page(RvLogLevel.Debug) }

    @Test fun debugDark() = compose.rvShot("settings-log-level-debug", dark = true) { Page(RvLogLevel.Debug) }

    @Test fun debugFs115() = compose.rvShot("settings-log-level-debug", dark = false, fontScale = RV_FS115) { Page(RvLogLevel.Debug) }

    @Test fun debugFs200() = compose.rvShot("settings-log-level-debug", dark = false, fontScale = RV_FS200) { Page(RvLogLevel.Debug) }

    @Test
    fun aRowChoosesItsLevel() {
        val picked = mutableListOf<RvLogLevel>()
        compose.setContent { RvTheme(seedColorScheme(Seed.Cyan.argb, false), false) { RvLogLevelScreen(RvLogLevel.Info, { picked += it }, onBack = {}) } }
        compose.onNodeWithText(rvText(R.string.rv_log_info)).assertIsSelected()
        compose.onNodeWithText(rvText(R.string.rv_log_debug)).performClick()
        compose.onNodeWithText(rvText(R.string.rv_log_error)).performClick()
        assertEquals(listOf(RvLogLevel.Debug, RvLogLevel.Error), picked)
    }

    @Test
    fun debugAddsNoRowLineAndTheFooterTellsTheHour() {
        compose.setContent { RvTheme(seedColorScheme(Seed.Cyan.argb, false), false) { Page(RvLogLevel.Debug) } }
        compose.onNodeWithText(rvText(R.string.rv_log_debug)).assertIsSelected()
        // 資訊's 預設 is the only second line on the card.
        compose.onAllNodes(hasText(rvText(R.string.rv_log_default))).assertCountEquals(1)
        val footer = RuntimeEnvironment.getApplication().resources.getQuantityString(R.plurals.rv_log_debug_footer, RV_DEBUG_HOURS, RV_DEBUG_HOURS)
        compose.onNodeWithText(footer).assertExists()
    }

    @Test
    fun theRowsAreOneRadioGroup() {
        compose.setContent { RvTheme(seedColorScheme(Seed.Cyan.argb, false), false) { Page(RvLogLevel.Info) } }
        compose.assertOneRadioGroup(RvLogLevel.entries.size)
    }

    @Composable
    private fun Page(level: RvLogLevel) = RvLogLevelScreen(level, onSelect = {}, onBack = {})
}
