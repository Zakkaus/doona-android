package dev.doona.android.prototype.rv.stats

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
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.TimeZone

/**
 * The Revolut-style 統計 sample, light and dark, in the scheme of the owner's wallpaper, as one long capture of the whole
 * page (goldens `proto-rv-stats-light` and `-dark`): the window is as tall as the page, so nothing scrolls. The paged
 * tiles show two at a time, so dark starts them on the second page: 上傳 and 連線次數 with its v0.2 tag.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h2144dp-notnight-xhdpi")
class RvStatsShotTest {
    @get:Rule
    val compose = createComposeRule()

    private val dir = System.getProperty("doona.shots") ?: "src/test/screenshots"
    private val zone = TimeZone.getDefault()

    // The page reads the hour and the update time in the device's zone; UTC keeps the golden the same on every machine.
    @Before
    fun utc() = TimeZone.setDefault(TimeZone.getTimeZone("UTC"))

    @After
    fun restoreZone() = TimeZone.setDefault(zone)

    @Test
    fun statsLight() = shot(dark = false) { RvStatsScreen(rvStatsSample(), onBack = {}) }

    @Test
    fun statsDark() = shot(dark = true) { RvStatsScreen(rvStatsSample(), onBack = {}, initialTile = 1) }

    private fun shot(dark: Boolean, content: @Composable () -> Unit) {
        RuntimeEnvironment.setQualifiers(if (dark) "+night" else "+notnight")
        compose.setContent {
            DoonaTheme(dark) {
                PhoneFrame {
                    RvTheme(seedColorScheme(Seed.Cyan.argb, dark), dark, content)
                }
            }
        }
        compose.drawFully()
        compose.onRoot().captureToImage().asAndroidBitmap().captureRoboImage("$dir/proto-rv-stats-${if (dark) "dark" else "light"}.png")
    }
}
