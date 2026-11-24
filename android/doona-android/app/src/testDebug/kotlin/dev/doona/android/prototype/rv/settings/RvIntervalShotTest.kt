package dev.doona.android.prototype.rv.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.doona.android.R
import dev.doona.android.prototype.rv.RV_FS115
import dev.doona.android.prototype.rv.RV_FS200
import dev.doona.android.prototype.rv.RvTheme
import dev.doona.android.prototype.rv.Seed
import dev.doona.android.prototype.rv.data.RvData
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
 * 更新間隔 (`proto-rv-settings-interval-*`), light, dark, fs115 and fs200, at the sample's 12 hours; then that a preset
 * row chooses its hours and 自訂 opens the field.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvIntervalShotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test fun light() = compose.rvShot("settings-interval", dark = false) { Page() }

    @Test fun dark() = compose.rvShot("settings-interval", dark = true) { Page() }

    @Test fun fs115() = compose.rvShot("settings-interval", dark = false, fontScale = RV_FS115) { Page() }

    @Test fun fs200() = compose.rvShot("settings-interval", dark = false, fontScale = RV_FS200) { Page() }

    @Test
    fun aPresetChoosesItsHoursAndCustomOpensTheField() {
        val picked = mutableListOf<Int>()
        var custom = 0
        compose.setContent {
            RvTheme(seedColorScheme(Seed.Cyan.argb, false), false) { RvIntervalScreen(RvData.UPDATE_HOURS, { picked += it }, onBack = {}, onCustom = { custom++ }) }
        }
        compose.onNodeWithText(hours(RvData.UPDATE_HOURS)).assertIsSelected()
        compose.onNodeWithText(hours(1)).performClick()
        compose.onNodeWithText(hours(24)).performClick()
        compose.onNodeWithText(rvText(R.string.rv_settings_dns_custom)).performClick()
        assertEquals(listOf(1, 24), picked)
        assertEquals(1, custom)
    }

    private fun hours(n: Int) = RuntimeEnvironment.getApplication().resources.getQuantityString(R.plurals.rv_interval_hours, n, n)

    @Composable
    private fun Page() = RvIntervalScreen(RvData.UPDATE_HOURS, onSelect = {}, onBack = {})
}
