package dev.doona.android.prototype.rv.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.doona.android.R
import dev.doona.android.prototype.rv.RV_FS115
import dev.doona.android.prototype.rv.RV_FS200
import dev.doona.android.prototype.rv.RvTheme
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
 * 日誌等級 at its default 資訊 (`proto-rv-settings-log-level-*`) and with 除錯 on, 52 minutes before it returns to 資訊
 * (`-log-level-debug-*`), each light, dark, fs115 and fs200; then that a row chooses its level and the countdown shows
 * only while 除錯 is on, following the minutes its caller gives.
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
        compose.setContent { RvTheme(seedColorScheme(Seed.Cyan.argb, false), false) { RvLogLevelScreen(RvLogLevel.Info, RV_SAMPLE_DEBUG_LEFT, { picked += it }, onBack = {}) } }
        compose.onNodeWithText(rvText(R.string.rv_log_info)).assertIsSelected()
        compose.onNodeWithText(rvText(R.string.rv_log_debug)).performClick()
        compose.onNodeWithText(rvText(R.string.rv_log_error)).performClick()
        assertEquals(listOf(RvLogLevel.Debug, RvLogLevel.Error), picked)
        compose.onNodeWithText(left()).assertDoesNotExist()
    }

    @Test
    fun debugCountsDownToInfo() {
        val level = mutableStateOf(RvLogLevel.Debug)
        val minutes = mutableIntStateOf(RV_SAMPLE_DEBUG_LEFT)
        compose.setContent { RvTheme(seedColorScheme(Seed.Cyan.argb, false), false) { RvLogLevelScreen(level.value, minutes.intValue, onSelect = {}, onBack = {}) } }
        compose.onNodeWithText(left()).assertExists()
        minutes.intValue = RV_SAMPLE_DEBUG_LEFT - 1
        compose.onNodeWithText(left(RV_SAMPLE_DEBUG_LEFT - 1)).assertExists()
        compose.onNodeWithText(left()).assertDoesNotExist()
        level.value = RvLogLevel.Info
        compose.onNodeWithText(left(RV_SAMPLE_DEBUG_LEFT - 1)).assertDoesNotExist()
    }

    private fun left(minutes: Int = RV_SAMPLE_DEBUG_LEFT) = RuntimeEnvironment.getApplication().resources.getQuantityString(R.plurals.rv_log_debug_left, minutes, minutes)

    @Test
    fun theRowsAreOneRadioGroup() {
        compose.setContent { RvTheme(seedColorScheme(Seed.Cyan.argb, false), false) { Page(RvLogLevel.Info) } }
        compose.assertOneRadioGroup(RvLogLevel.entries.size)
    }

    @Composable
    private fun Page(level: RvLogLevel) = RvLogLevelScreen(level, RV_SAMPLE_DEBUG_LEFT, onSelect = {}, onBack = {})
}
