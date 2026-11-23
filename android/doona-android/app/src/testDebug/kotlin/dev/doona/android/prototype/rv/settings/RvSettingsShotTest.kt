package dev.doona.android.prototype.rv.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.doona.android.prototype.rv.Seed
import dev.doona.android.prototype.rv.assertWhole
import dev.doona.android.prototype.rv.rvShot
import dev.doona.android.prototype.rv.seedColorScheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Revolut-style settings sample, light and dark, in the scheme of the owner's wallpaper: the 設定 page captured in
 * one long frame (goldens `proto-rv-settings-light` and `-dark`), and the 主題 page it opens
 * (`proto-rv-settings-theme-light` and `-dark`).
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
    fun themeLight() = compose.rvShot("settings-theme", dark = false) { Theme() }

    @Test
    fun themeDark() = compose.rvShot("settings-theme", dark = true) { Theme() }

    @Composable
    private fun Settings() = RvSettingsScreen(rvSampleSettings(), onChange = {}, onOpenTheme = {})

    @Composable
    private fun Theme() =
        RvThemeScreen(rvSampleAppearance(), onChange = {}, light = seedColorScheme(Seed.Cyan.argb, false), dark = seedColorScheme(Seed.Cyan.argb, true), onBack = {})

    private companion object {
        /** As tall as the whole 設定 page, so its end shows as the phone shows it: the last card 28 dp above the bottom navigation. */
        const val LONG_PAGE = "+h1583dp"
    }
}
