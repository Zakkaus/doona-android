package dev.doona.android.app

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.doona.android.engine.fake.FakeEngine
import dev.doona.android.engine.fake.FakeInstalledApps
import dev.doona.android.testing.PhoneFrame
import dev.doona.android.testing.SeedTheme
import dev.doona.android.testing.checkAccessibility
import dev.doona.android.testing.drawFully
import dev.doona.android.testing.showsContent
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The app's frame around a tab root, drawn through [DoonaApp] itself: the old 策略 page above RV's floating nav with
 * the app's four tabs, 策略 selected. Golden `src/test/screenshots/app-nav-light.png`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class AppNavShotTest {
    @get:Rule
    val compose = createComposeRule()

    private val dir = System.getProperty("doona.shots") ?: "src/test/screenshots"

    @Test
    fun theFloatingNavFramesATabRoot() {
        compose.setContent {
            SeedTheme {
                PhoneFrame {
                    DoonaApp(FakeEngine(FakeEngine.SAMPLE_GROUPS), FakeInstalledApps(), onConnect = {}, start = listOf(ActivityKey, PoliciesKey))
                }
            }
        }
        compose.drawFully()
        // The nav's tabs, by label: the old 策略 page has tabs of its own.
        val tab = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        compose.onAllNodes(tab and hasText("活動")).assertCountEquals(1)
        compose.onAllNodes(tab and hasText("設定")).assertCountEquals(1)
        compose.onAllNodes(tab and hasText("連線")).assertCountEquals(1)
        val image = compose.onRoot().captureToImage()
        assertTrue("app-nav-light: the content area shows nothing but fills", image.showsContent(compose.density.density))
        image.asAndroidBitmap().captureRoboImage("$dir/app-nav-light.png")
        compose.onRoot().checkAccessibility()
    }
}
