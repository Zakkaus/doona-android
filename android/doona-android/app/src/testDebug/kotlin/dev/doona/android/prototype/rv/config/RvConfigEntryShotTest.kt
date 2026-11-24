package dev.doona.android.prototype.rv.config

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.prototype.rv.RV_FS115
import dev.doona.android.prototype.rv.RV_FS200
import dev.doona.android.prototype.rv.Seed
import dev.doona.android.prototype.rv.Utc
import dev.doona.android.prototype.rv.assertWhole
import dev.doona.android.prototype.rv.policy.RvPolicyRoutes
import dev.doona.android.prototype.rv.policy.RvPolicyScreen
import dev.doona.android.prototype.rv.policy.RvPolicyState
import dev.doona.android.prototype.rv.rvShot
import dev.doona.android.prototype.rv.seedColorScheme
import dev.doona.android.prototype.rv.settings.RvSettingsRoutes
import dev.doona.android.prototype.rv.settings.RvSettingsScreen
import dev.doona.android.prototype.rv.settings.rvSampleSettings
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The configuration's entry points (PV-f item 5), samples only, behind the pages' preview flag `configRev`: 策略 in 規則
 * with 設定檔 under 範本 (`proto-rv-policy-config-entry-*`) and 設定 with 設定檔 in 進階
 * (`proto-rv-settings-config-entry-*`). Each is recorded as its own counterpart is, in the same window and from the
 * same scroll, so a diff against it shows what the row moved: 策略 is whole at light and dark and the phone's first
 * screen at fs115 and fs200 (`proto-rv-policy-*`; the row is below that fold, so those two show the page above it and
 * nothing scrolls, which would collapse the nav to its glyphs); 設定 is whole at every size, as its own goldens are.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvConfigEntryShotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    @Test
    @Config(qualifiers = WHOLE_POLICY)
    fun policyLight() = compose.rvShot("policy-config-entry", dark = false, before = { assertWhole() }) { Policy() }

    @Test
    @Config(qualifiers = WHOLE_POLICY)
    fun policyDark() = compose.rvShot("policy-config-entry", dark = true, before = { assertWhole() }) { Policy() }

    @Test fun policyFs115() = compose.rvShot("policy-config-entry", dark = false, fontScale = RV_FS115) { Policy() }

    @Test fun policyFs200() = compose.rvShot("policy-config-entry", dark = false, fontScale = RV_FS200) { Policy() }

    @Test
    @Config(qualifiers = WHOLE_SETTINGS)
    fun settingsLight() = compose.rvShot("settings-config-entry", dark = false, before = { assertWhole() }) { Settings() }

    @Test
    @Config(qualifiers = WHOLE_SETTINGS)
    fun settingsDark() = compose.rvShot("settings-config-entry", dark = true, before = { assertWhole() }) { Settings() }

    @Test
    @Config(qualifiers = WHOLE_SETTINGS_FS115)
    fun settingsFs115() = compose.rvShot("settings-config-entry", dark = false, before = { assertWhole() }, fontScale = RV_FS115) { Settings() }

    @Test
    @Config(qualifiers = WHOLE_SETTINGS_FS200)
    fun settingsFs200() = compose.rvShot("settings-config-entry", dark = false, before = { assertWhole() }, fontScale = RV_FS200) { Settings() }

    @Test
    fun policysRowOpensTheOverview() {
        var opened = false
        compose.setContent { Themed { RvPolicyScreen(RvPolicyState(), onChange = {}, routes = RvPolicyRoutes(config = { opened = true }), configRev = RvConfigData.REV) } }
        compose.onNodeWithText(CONFIG).performScrollTo().performClick()
        assertTrue(opened)
    }

    @Test
    fun settingsLinkOpensTheOverview() {
        var opened = false
        compose.setContent {
            Themed { RvSettingsScreen(rvSampleSettings(), onChange = {}, onOpenTheme = {}, routes = RvSettingsRoutes(config = { opened = true }), configRev = RvConfigData.REV) }
        }
        compose.onNodeWithText(CONFIG).performScrollTo().performClick()
        assertTrue(opened)
    }

    @Composable
    private fun Themed(content: @Composable () -> Unit) = RvTheme(seedColorScheme(Seed.Cyan.argb, false), false, content)

    @Composable
    private fun Policy() = RvPolicyScreen(RvPolicyState(), onChange = {}, configRev = RvConfigData.REV)

    @Composable
    private fun Settings() = RvSettingsScreen(rvSampleSettings(), onChange = {}, onOpenTheme = {}, configRev = RvConfigData.REV)

    private companion object {
        const val WHOLE_POLICY = "+h1990dp"
        const val WHOLE_SETTINGS = "+h1846dp"
        const val WHOLE_SETTINGS_FS115 = "+h1871dp"
        const val WHOLE_SETTINGS_FS200 = "+h2394dp"
        const val CONFIG = "設定檔"
    }
}
