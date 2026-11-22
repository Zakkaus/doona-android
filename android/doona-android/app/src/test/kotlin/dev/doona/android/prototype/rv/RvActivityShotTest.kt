package dev.doona.android.prototype.rv

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.feature.activity.sampleActivityState
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
 * The Revolut-style sample of the 活動 screen, light and dark, in the scheme of the owner's wallpaper: goldens
 * `proto-rv-activity-light` and `-dark`, for the owner to judge next to Revolut's home screen.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvActivityShotTest {
    @get:Rule
    val compose = createComposeRule()

    private val dir = System.getProperty("doona.shots") ?: "src/test/screenshots"

    @Test
    fun light() = shot(dark = false)

    @Test
    fun dark() = shot(dark = true)

    private fun shot(dark: Boolean) {
        RuntimeEnvironment.setQualifiers(if (dark) "+night" else "+notnight")
        compose.setContent {
            DoonaTheme(dark) {
                PhoneFrame {
                    RvTheme(seedColorScheme(Seed.Cyan.argb, dark), dark) {
                        RvActivityScreen(sampleActivityState(), onAction = {}, onConnect = {}, onOpenGroup = {})
                    }
                }
            }
        }
        compose.drawFully()
        compose.onRoot().captureToImage().asAndroidBitmap().captureRoboImage("$dir/proto-rv-activity-${if (dark) "dark" else "light"}.png")
    }
}
