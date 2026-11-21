package dev.tunbench.preview

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import dev.tunbench.preview.model.ControlState
import dev.tunbench.preview.theme.TunBenchTheme
import dev.tunbench.preview.ui.AppShell
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Control screen and the Settings page at 412 dp wide, light and dark, zh-TW and en, on the fallback
 * scheme (Robolectric has no wallpaper colours), and the Control screen on four wallpaper seeds. Each
 * shot is one 915 dp screen.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ControlScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val dir = System.getProperty("tunbench.shots") ?: "build/outputs/roborazzi"

    private fun shoot(name: String, settings: Boolean = false) {
        compose.setContent {
            TunBenchTheme(dynamicColor = false) { AppShell(ControlState.sample(), initialSettingsOpen = settings) }
        }
        compose.onRoot().captureRoboImage("$dir/$name.png")
    }

    private fun shootSeed(seed: Seed, dark: Boolean) {
        compose.setContent {
            TunBenchTheme(fixedColorScheme = seedColorScheme(seed.argb, dark)) { AppShell(ControlState.sample()) }
        }
        compose.onRoot().captureRoboImage("$dir/seed-${seed.name.lowercase()}-${if (dark) "dark" else "light"}.png")
    }

    @Test
    @Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xxhdpi")
    fun seedCyanLight() = shootSeed(Seed.Cyan, dark = false)

    @Test
    @Config(qualifiers = "zh-rTW-w412dp-h915dp-night-xxhdpi")
    fun seedCyanDark() = shootSeed(Seed.Cyan, dark = true)

    @Test
    @Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xxhdpi")
    fun seedGreenLight() = shootSeed(Seed.Green, dark = false)

    @Test
    @Config(qualifiers = "zh-rTW-w412dp-h915dp-night-xxhdpi")
    fun seedGreenDark() = shootSeed(Seed.Green, dark = true)

    @Test
    @Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xxhdpi")
    fun seedPurpleLight() = shootSeed(Seed.Purple, dark = false)

    @Test
    @Config(qualifiers = "zh-rTW-w412dp-h915dp-night-xxhdpi")
    fun seedPurpleDark() = shootSeed(Seed.Purple, dark = true)

    @Test
    @Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xxhdpi")
    fun seedWarmLight() = shootSeed(Seed.Warm, dark = false)

    @Test
    @Config(qualifiers = "zh-rTW-w412dp-h915dp-night-xxhdpi")
    fun seedWarmDark() = shootSeed(Seed.Warm, dark = true)

    @Test
    @Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xxhdpi")
    fun controlZhLight() = shoot("control-zh-TW-light")

    @Test
    @Config(qualifiers = "zh-rTW-w412dp-h915dp-night-xxhdpi")
    fun controlZhDark() = shoot("control-zh-TW-dark")

    @Test
    @Config(qualifiers = "en-rUS-w412dp-h915dp-notnight-xxhdpi")
    fun controlEnLight() = shoot("control-en-light")

    @Test
    @Config(qualifiers = "en-rUS-w412dp-h915dp-night-xxhdpi")
    fun controlEnDark() = shoot("control-en-dark")

    /** The owner's Pixel runs font_scale 1.15 (design/reference-board.zh-TW.md). */
    @Test
    @Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xxhdpi", fontScale = 1.15f)
    fun controlZhLightFontScale() = shoot("control-zh-TW-light-font1.15")

    @Test
    @Config(qualifiers = "zh-rTW-w412dp-h915dp-night-xxhdpi", fontScale = 1.15f)
    fun controlZhDarkFontScale() = shoot("control-zh-TW-dark-font1.15")

    @Test
    @Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xxhdpi")
    fun settingsZhLight() = shoot("settings-zh-TW-light", settings = true)

    @Test
    @Config(qualifiers = "zh-rTW-w412dp-h915dp-night-xxhdpi")
    fun settingsZhDark() = shoot("settings-zh-TW-dark", settings = true)

    @Test
    @Config(qualifiers = "en-rUS-w412dp-h915dp-notnight-xxhdpi")
    fun settingsEnLight() = shoot("settings-en-light", settings = true)

    @Test
    @Config(qualifiers = "en-rUS-w412dp-h915dp-night-xxhdpi")
    fun settingsEnDark() = shoot("settings-en-dark", settings = true)

    /** The overflow menu, open over the Control screen; the popup is its own window. */
    @OptIn(ExperimentalRoborazziApi::class)
    @Test
    @Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xxhdpi")
    fun menuZhLight() {
        compose.setContent {
            TunBenchTheme(dynamicColor = false) { AppShell(ControlState.sample()) }
        }
        compose.onNodeWithContentDescription("更多選項").performClick()
        compose.waitForIdle()
        captureScreenRoboImage("$dir/menu-zh-TW-light.png")
    }
}
