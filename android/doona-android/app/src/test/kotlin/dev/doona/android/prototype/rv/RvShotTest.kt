package dev.doona.android.prototype.rv

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import dev.doona.android.designsystem.theme.DoonaTheme
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
 * The Revolut-style samples, light and dark, in the scheme of the owner's wallpaper, for the owner to judge next to
 * Revolut's screens: the 活動 screen (goldens `proto-rv-activity-light` and `-dark`, and disconnected in
 * `proto-rv-activity-idle-light`), the 「Proxy」 group's node screen (`proto-rv-nodes-light` and `-dark`) and the
 * traffic page a tile opens (`proto-rv-traffic-light` and `-dark`, and held at one second in `proto-rv-traffic-scrub-dark`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvShotTest {
    @get:Rule
    val compose = createComposeRule()

    private val dir = System.getProperty("doona.shots") ?: "src/test/screenshots"
    private val zone = TimeZone.getDefault()

    // The node screen prints the subscription's update time in the device's zone; UTC keeps the golden the same on
    // every machine.
    @Before
    fun utc() = TimeZone.setDefault(TimeZone.getTimeZone("UTC"))

    @After
    fun restoreZone() = TimeZone.setDefault(zone)

    @Test
    fun activityLight() = shot("activity", dark = false) { RvActivityScreen(rvSampleActivity(), onAction = {}, onConnect = {}, onOpenGroup = {}, onOpenTraffic = {}) }

    @Test
    fun activityDark() = shot("activity", dark = true) { RvActivityScreen(rvSampleActivity(), onAction = {}, onConnect = {}, onOpenGroup = {}, onOpenTraffic = {}) }

    @Test
    fun activityIdleLight() = shot("activity-idle", dark = false) { RvActivityScreen(rvIdleActivity(), onAction = {}, onConnect = {}, onOpenGroup = {}, onOpenTraffic = {}) }

    @Test
    fun nodesLight() = shot("nodes", dark = false) { RvNodesScreen(sampleNodesState(), onBack = {}, onTestAll = {}, onSelect = {}) }

    @Test
    fun nodesDark() = shot("nodes", dark = true) { RvNodesScreen(sampleNodesState(), onBack = {}, onTestAll = {}, onSelect = {}) }

    @Test
    fun trafficLight() = shot("traffic", dark = false) { RvTrafficScreen(rvSampleActivity(), RV_SAMPLE_CONNECTION, onBack = {}) }

    @Test
    fun trafficDark() = shot("traffic", dark = true) { RvTrafficScreen(rvSampleActivity(), RV_SAMPLE_CONNECTION, onBack = {}) }

    @Test
    fun trafficScrubDark() = shot("traffic-scrub", dark = true) {
        RvTrafficScreen(rvSampleActivity(), RV_SAMPLE_CONNECTION, onBack = {}, initialScrub = RV_SAMPLE_SCRUB)
    }

    private fun shot(screen: String, dark: Boolean, content: @Composable () -> Unit) {
        RuntimeEnvironment.setQualifiers(if (dark) "+night" else "+notnight")
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
}
