package dev.doona.android.prototype.rv

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.doona.android.prototype.rv.data.RvData
import dev.doona.android.prototype.rv.data.RvRouteMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Revolut-style samples, light and dark, in the scheme of the owner's wallpaper, for the owner to judge next to
 * Revolut's screens: the 活動 screen (goldens `proto-rv-activity-light` and `-dark`, routing by rules; in 全域 in
 * `proto-rv-activity-global-*`; disconnected in `proto-rv-activity-idle-*`, scrolled with the bottom bar collapsed in
 * `proto-rv-activity-scrolled-*`, and the whole page in one tall capture in `proto-rv-activity-full-*`, at 1.15× and 2.0× too) and the traffic page a tile opens (`proto-rv-traffic-*`, and
 * held at one second in `proto-rv-traffic-scrub-*`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvShotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    @Test
    fun activityLight() = activity("activity", dark = false)

    @Test
    fun activityDark() = activity("activity", dark = true)

    @Test
    fun activityGlobalLight() = compose.rvShot("activity-global", dark = false) { Global() }

    @Test
    fun activityGlobalDark() = compose.rvShot("activity-global", dark = true) { Global() }

    @Test
    fun activityIdleLight() = compose.rvShot("activity-idle", dark = false) { RvActivityScreen(rvIdleActivity(), onAction = {}, onConnect = {}) }

    @Test
    fun activityIdleDark() = compose.rvShot("activity-idle", dark = true) { RvActivityScreen(rvIdleActivity(), onAction = {}, onConnect = {}) }

    @Test
    fun activityScrolledLight() = activity("activity-scrolled", dark = false) { rvScrollBy(SCROLLED_DP) }

    @Test
    fun activityScrolledDark() = activity("activity-scrolled", dark = true) { rvScrollBy(SCROLLED_DP) }

    @Test
    @Config(qualifiers = FULL_PAGE)
    fun activityFullLight() = activity("activity-full", dark = false) { assertWhole() }

    @Test
    @Config(qualifiers = FULL_PAGE)
    fun activityFullDark() = activity("activity-full", dark = true) { assertWhole() }

    @Test
    @Config(qualifiers = FULL_PAGE_FS115)
    fun activityFullFs115() = activity("activity-full", dark = false, fontScale = RV_FS115) { assertWhole() }

    @Test
    @Config(qualifiers = FULL_PAGE_FS200)
    fun activityFullFs200() = activity("activity-full", dark = false, fontScale = RV_FS200) { assertWhole() }

    @Test
    fun trafficLight() = traffic("traffic", dark = false)

    @Test
    fun trafficDark() = traffic("traffic", dark = true)

    @Test
    fun trafficFs115() = traffic("traffic", dark = false, fontScale = RV_FS115)

    @Test
    fun trafficFs200() = traffic("traffic", dark = false, fontScale = RV_FS200)

    @Test
    fun trafficScrubLight() = traffic("traffic-scrub", dark = false, scrub = SCRUB)

    @Test
    fun trafficScrubDark() = traffic("traffic-scrub", dark = true, scrub = SCRUB)

    @Test
    @Config(qualifiers = RV_EN)
    fun activityEn() = activity("activity-en", dark = false)

    private fun activity(screen: String, dark: Boolean, fontScale: Float = 1f, before: ComposeContentTestRule.() -> Unit = {}) =
        compose.rvShot(screen, dark, before, fontScale) { RvActivityScreen(rvSampleActivity(), onAction = {}, onConnect = {}) }

    @Composable
    private fun Global() = RvActivityScreen(rvSampleActivity(), onAction = {}, onConnect = {}, mode = RvRouteMode.Global)

    private fun traffic(screen: String, dark: Boolean, scrub: Int? = null, fontScale: Float = 1f) =
        compose.rvShot(screen, dark, fontScale = fontScale) { RvTrafficScreen(rvSampleActivity(), RvData.session, onBack = {}, initialScrub = scrub) }

    private companion object {
        /** The scrolled golden moves the content this far, past the header, so the bottom bar collapses. */
        const val SCROLLED_DP = 400f

        /** A screen as tall as the whole 活動 page, for the full captures. */
        const val FULL_PAGE = "+h1951dp"

        /** The same at 1.15× and 2.0×, where the page is taller. */
        const val FULL_PAGE_FS115 = "+h2011dp"
        const val FULL_PAGE_FS200 = "+h2805dp"

        /** The second the scrubbed golden holds a finger on: inside the sustained download. */
        const val SCRUB = 30
    }
}
