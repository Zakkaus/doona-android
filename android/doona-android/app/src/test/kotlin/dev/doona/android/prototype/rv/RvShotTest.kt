package dev.doona.android.prototype.rv

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import com.github.takahirom.roborazzi.captureRoboImage
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.testing.PhoneFrame
import dev.doona.android.testing.drawFully
import org.junit.After
import org.junit.Assert.assertEquals
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
 * Revolut's screens: the 活動 screen (goldens `proto-rv-activity-light` and `-dark`, disconnected in
 * `proto-rv-activity-idle-light`, scrolled with the bottom bar collapsed in `proto-rv-activity-scrolled-light`, and the
 * whole page in one tall capture in `proto-rv-activity-full-light` and `-dark`) and the traffic page a tile opens
 * (`proto-rv-traffic-light` and `-dark`, and held at one second in `proto-rv-traffic-scrub-dark`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvShotTest {
    @get:Rule
    val compose = createComposeRule()

    private val dir = System.getProperty("doona.shots") ?: "src/test/screenshots"
    private val zone = TimeZone.getDefault()

    // The pages print times in the device's zone; UTC keeps the goldens the same on every machine.
    @Before
    fun utc() = TimeZone.setDefault(TimeZone.getTimeZone("UTC"))

    @After
    fun restoreZone() = TimeZone.setDefault(zone)

    @Test
    fun activityLight() = shot("activity", dark = false) { RvActivityScreen(rvSampleActivity(), onAction = {}, onConnect = {}) }

    @Test
    fun activityDark() = shot("activity", dark = true) { RvActivityScreen(rvSampleActivity(), onAction = {}, onConnect = {}) }

    @Test
    fun activityIdleLight() = shot("activity-idle", dark = false) { RvActivityScreen(rvIdleActivity(), onAction = {}, onConnect = {}) }

    @Test
    fun activityScrolledLight() = shot("activity-scrolled", dark = false, before = { scrollBy(SCROLLED_DP) }) {
        RvActivityScreen(rvSampleActivity(), onAction = {}, onConnect = {})
    }

    @Test
    @Config(qualifiers = FULL_PAGE)
    fun activityFullLight() = shot("activity-full", dark = false, before = ::assertWhole) {
        RvActivityScreen(rvSampleActivity(), onAction = {}, onConnect = {})
    }

    @Test
    @Config(qualifiers = FULL_PAGE)
    fun activityFullDark() = shot("activity-full", dark = true, before = ::assertWhole) {
        RvActivityScreen(rvSampleActivity(), onAction = {}, onConnect = {})
    }

    @Test
    fun trafficLight() = shot("traffic", dark = false) { RvTrafficScreen(rvSampleActivity(), RV_SAMPLE_CONNECTION, onBack = {}) }

    @Test
    fun trafficDark() = shot("traffic", dark = true) { RvTrafficScreen(rvSampleActivity(), RV_SAMPLE_CONNECTION, onBack = {}) }

    @Test
    fun trafficScrubDark() = shot("traffic-scrub", dark = true) {
        RvTrafficScreen(rvSampleActivity(), RV_SAMPLE_CONNECTION, onBack = {}, initialScrub = RV_SAMPLE_SCRUB)
    }

    /** Scrolls the page's content up by [dp], as a finger would, and lets the bottom bar finish collapsing. */
    private fun scrollBy(dp: Float) {
        compose.onNode(hasScrollAction()).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, dp * compose.density.density) }
        compose.waitForIdle()
    }

    /** The tall capture holds the whole page: nothing is left to scroll. */
    private fun assertWhole() {
        val range = compose.onNode(hasScrollAction()).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertEquals("the page outgrew $FULL_PAGE", 0f, range.maxValue())
    }

    private fun shot(screen: String, dark: Boolean, before: () -> Unit = {}, content: @Composable () -> Unit) {
        RuntimeEnvironment.setQualifiers(if (dark) "+night" else "+notnight")
        compose.setContent {
            DoonaTheme(dark) {
                PhoneFrame {
                    RvTheme(seedColorScheme(Seed.Cyan.argb, dark), dark, content)
                }
            }
        }
        before()
        compose.drawFully()
        compose.onRoot().captureToImage().asAndroidBitmap().captureRoboImage("$dir/proto-rv-$screen-${if (dark) "dark" else "light"}.png")
    }

    private companion object {
        /** The scrolled golden moves the content this far, past the header, so the bottom bar collapses. */
        const val SCROLLED_DP = 400f

        /** A screen as tall as the whole 活動 page, 915 dp plus the 777.5 dp it scrolls, for the full captures. */
        const val FULL_PAGE = "+h1693dp"
    }
}
