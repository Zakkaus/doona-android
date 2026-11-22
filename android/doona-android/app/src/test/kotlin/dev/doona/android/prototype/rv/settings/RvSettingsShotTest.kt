package dev.doona.android.prototype.rv.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.prototype.rv.RvTheme
import dev.doona.android.prototype.rv.Seed
import dev.doona.android.prototype.rv.seedColorScheme
import dev.doona.android.testing.PhoneFrame
import dev.doona.android.testing.drawFully
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Revolut-style settings sample, in the scheme of the owner's wallpaper: the 設定 page captured in one long frame,
 * light and dark (goldens `proto-rv-settings-light` and `-dark`), and the 主題 page it opens (`proto-rv-settings-theme-light`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvSettingsShotTest {
    @get:Rule
    val compose = createComposeRule()

    private val dir = System.getProperty("doona.shots") ?: "src/test/screenshots"

    @Test
    fun settingsLight() = shot("settings", dark = false, heightDp = LONG_PAGE) { RvSettingsScreen(rvSampleSettings(), onChange = {}, onOpenTheme = {}) }

    @Test
    fun settingsDark() = shot("settings", dark = true, heightDp = LONG_PAGE) { RvSettingsScreen(rvSampleSettings(), onChange = {}, onOpenTheme = {}) }

    @Test
    fun themeLight() = shot("settings-theme", dark = false, heightDp = PHONE) {
        RvThemeScreen(rvSampleAppearance(), onChange = {}, light = seedColorScheme(Seed.Cyan.argb, false), dark = seedColorScheme(Seed.Cyan.argb, true), onBack = {})
    }

    private fun shot(screen: String, dark: Boolean, heightDp: Int, content: @Composable () -> Unit) {
        RuntimeEnvironment.setQualifiers("+h${heightDp}dp-${if (dark) "night" else "notnight"}")
        compose.setContent {
            DoonaTheme(dark) {
                PhoneFrame {
                    RvTheme(seedColorScheme(Seed.Cyan.argb, dark), dark, content)
                }
            }
        }
        compose.drawFully()
        compose.onRoot().captureToImage().asAndroidBitmap().captureRoboImage("$dir/proto-rv-$screen-${if (dark) "dark" else "light"}.png")
    }

    private companion object {
        const val PHONE = 915

        /** Tall enough that the whole 設定 page shows, down to the bottom navigation. */
        const val LONG_PAGE = 1460
    }
}
