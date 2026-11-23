package dev.doona.android.prototype.rv.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.doona.android.R
import dev.doona.android.prototype.rv.RV_FS115
import dev.doona.android.prototype.rv.RV_FS200
import dev.doona.android.prototype.rv.RvTheme
import dev.doona.android.prototype.rv.Seed
import dev.doona.android.prototype.rv.assertWhole
import dev.doona.android.prototype.rv.rvShot
import dev.doona.android.prototype.rv.rvText
import dev.doona.android.prototype.rv.seedColorScheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Revolut-style settings sample, light and dark, in the scheme of the owner's wallpaper: the 設定 page captured in
 * one long frame (goldens `proto-rv-settings-light`, `-dark`, `-fs115` and `-fs200`), and the 主題 page it opens
 * (`proto-rv-settings-theme-light` and `-dark`), and the DNS picker of 進階 (`proto-rv-settings-dns-*`); and that
 * 進階's 回報問題 row opens the report page, and the 依 App and licences rows theirs.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvSettingsShotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    @Config(qualifiers = LONG_PAGE)
    fun settingsLight() = compose.rvShot("settings", dark = false, before = { assertWhole() }) { Settings() }

    @Test
    @Config(qualifiers = LONG_PAGE)
    fun settingsDark() = compose.rvShot("settings", dark = true, before = { assertWhole() }) { Settings() }

    @Test
    @Config(qualifiers = LONG_PAGE_FS115)
    fun settingsFs115() = compose.rvShot("settings", dark = false, before = { assertWhole() }, fontScale = RV_FS115) { Settings() }

    @Test
    @Config(qualifiers = LONG_PAGE_FS200)
    fun settingsFs200() = compose.rvShot("settings", dark = false, before = { assertWhole() }, fontScale = RV_FS200) { Settings() }

    @Test
    @Config(qualifiers = LONG_PAGE)
    fun advancedOpensTheReport() {
        var opened = 0
        compose.setContent {
            RvTheme(seedColorScheme(Seed.Cyan.argb, false), false) {
                RvSettingsScreen(rvSampleSettings(), onChange = {}, onOpenTheme = {}, routes = RvSettingsRoutes(report = { opened++ }))
            }
        }
        compose.onNodeWithText(rvText(R.string.rv_report_title)).performClick()
        assertEquals(1, opened)
    }

    @Test
    @Config(qualifiers = LONG_PAGE)
    fun perAppAndLicensesOpenTheirPages() {
        var perApp = 0
        var licenses = 0
        compose.setContent {
            RvTheme(seedColorScheme(Seed.Cyan.argb, false), false) {
                RvSettingsScreen(rvSampleSettings(), onChange = {}, onOpenTheme = {}, routes = RvSettingsRoutes(perApp = { perApp++ }, licenses = { licenses++ }))
            }
        }
        compose.onNodeWithText(rvText(R.string.rv_settings_per_app)).performClick()
        compose.onNodeWithText(rvText(R.string.rv_settings_licenses)).performClick()
        assertEquals(1, perApp)
        assertEquals(1, licenses)
    }

    @Test
    fun themeLight() = compose.rvShot("settings-theme", dark = false) { Theme() }

    @Test
    fun themeDark() = compose.rvShot("settings-theme", dark = true) { Theme() }

    @Test
    fun dnsLight() = compose.rvShot("settings-dns", dark = false) { Dns() }

    @Test
    fun dnsDark() = compose.rvShot("settings-dns", dark = true) { Dns() }

    @Composable
    private fun Dns() = RvDnsScreen(RvDnsUpstream.Cloudflare, onSelect = {}, onBack = {})

    @Composable
    private fun Settings() = RvSettingsScreen(rvSampleSettings(), onChange = {}, onOpenTheme = {})

    @Composable
    private fun Theme() =
        RvThemeScreen(rvSampleAppearance(), onChange = {}, light = seedColorScheme(Seed.Cyan.argb, false), dark = seedColorScheme(Seed.Cyan.argb, true), onBack = {})

    private companion object {
        /**
         * As tall as the whole 設定 page, at each font size, so its end shows as the phone shows it: the last card 28 dp
         * above the bottom navigation.
         */
        const val LONG_PAGE = "+h1529dp"
        const val LONG_PAGE_FS115 = "+h1546dp"
        const val LONG_PAGE_FS200 = "+h1882dp"
    }
}
