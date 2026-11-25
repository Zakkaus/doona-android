package dev.doona.android.prototype.rv.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.feature.model.RvDnsUpstream
import dev.doona.android.prototype.rv.RV_EN
import dev.doona.android.prototype.rv.RV_FS115
import dev.doona.android.prototype.rv.RV_FS200
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
 * 進階's 回報問題 row opens the report page, and the 依 App and licences rows theirs; and that its top is the hero (the
 * owner's S1), with its title and no search capsule, and its round actions as far under the title as Revolut's
 * Security hero sets them.
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
    @Config(qualifiers = RV_EN)
    fun settingsEn() = compose.rvShot("settings-en", dark = false) { Settings() }

    @Test
    fun theTopIsTheHeroWithItsTitleAndNoSearch() {
        compose.setContent { RvTheme(seedColorScheme(Seed.Cyan.argb, false), false) { Settings() } }
        compose.onNode(hasText(rvText(R.string.hub_settings)) and isHeading()).assertExists()
        compose.onNodeWithText(rvText(R.string.rv_search_nodes)).assertDoesNotExist()
    }

    /**
     * The round actions sit [RvSettingsSpace.titleToActions] under the title's line box, so their discs fall as far
     * under the title's baseline as Revolut's Security hero puts them (revolut-08), within [BASELINE_NEAR]; S1's 24 dp
     * left 30.5.
     */
    @Test
    fun theActionsSitUnderTheTitleAsRevolutsSecurityHero() {
        compose.setContent { RvTheme(seedColorScheme(Seed.Cyan.argb, false), false) { Settings() } }
        val title = compose.onNode(hasText(rvText(R.string.hub_settings)) and isHeading())
        val box = title.getUnclippedBoundsInRoot()
        val layouts = mutableListOf<TextLayoutResult>()
        title.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        val baseline = box.top + with(compose.density) { layouts.single().firstBaseline.toDp() }
        val disc = compose.onNodeWithText(rvText(R.string.rv_settings_export_diagnostics)).getUnclippedBoundsInRoot().top
        assertEquals("line box to disc", RvSettingsSpace.titleToActions.value, (disc - box.bottom).value, NEAR)
        assertEquals("baseline to disc", REVOLUT_BASELINE_TO_DISC, (disc - baseline).value, BASELINE_NEAR)
    }

    @Test
    @Config(qualifiers = LONG_PAGE)
    fun advancedOpensTheReport() {
        var opened = 0
        compose.setContent {
            RvTheme(seedColorScheme(Seed.Cyan.argb, false), false) {
                RvSettingsScreenSample(rvSampleSettings(), onChange = {}, onOpenTheme = {}, routes = RvSettingsRoutes(report = { opened++ }))
            }
        }
        compose.onNodeWithText(rvText(R.string.rv_report_title)).performClick()
        assertEquals(1, opened)
    }

    @Test
    @Config(qualifiers = LONG_PAGE)
    fun perAppAndAboutOpenTheirPages() {
        var perApp = 0
        var about = 0
        compose.setContent {
            RvTheme(seedColorScheme(Seed.Cyan.argb, false), false) {
                RvSettingsScreenSample(rvSampleSettings(), onChange = {}, onOpenTheme = {}, routes = RvSettingsRoutes(perApp = { perApp++ }, about = { about++ }))
            }
        }
        compose.onNodeWithText(rvText(R.string.rv_settings_per_app)).performClick()
        compose.onNodeWithText(rvText(R.string.rv_about_title)).performClick()
        assertEquals(1, perApp)
        assertEquals(1, about)
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
    private fun Settings() = RvSettingsScreenSample(rvSampleSettings(), onChange = {}, onOpenTheme = {})

    @Composable
    private fun Theme() =
        RvThemeScreen(rvSampleAppearance(), onChange = {}, light = seedColorScheme(Seed.Cyan.argb, false), dark = seedColorScheme(Seed.Cyan.argb, true), onBack = {})

    private companion object {
        /**
         * As tall as the whole 設定 page, at each font size, so its end shows as the phone shows it: the last card 28 dp
         * above the bottom navigation.
         */
        const val LONG_PAGE = "+h1764dp"
        const val LONG_PAGE_FS115 = "+h1789dp"
        const val LONG_PAGE_FS200 = "+h2288dp"

        const val NEAR = 0.5f

        /** revolut-08 (Security): the title's baseline (row 674) to the discs' top (row 743), 69 px at 1.46 px/dp. */
        const val REVOLUT_BASELINE_TO_DISC = 47f
        const val BASELINE_NEAR = 2f
    }
}
