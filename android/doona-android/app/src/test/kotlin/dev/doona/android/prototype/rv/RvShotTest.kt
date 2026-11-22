package dev.doona.android.prototype.rv

import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performSemanticsAction
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
 * `proto-rv-activity-scrolled-*`, and the whole page in one tall capture in `proto-rv-activity-full-*`) and the traffic page a tile opens (`proto-rv-traffic-*`, and
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
    fun activityScrolledLight() = activity("activity-scrolled", dark = false) { scrollBy(SCROLLED_DP) }

    @Test
    fun activityScrolledDark() = activity("activity-scrolled", dark = true) { scrollBy(SCROLLED_DP) }

    @Test
    @Config(qualifiers = FULL_PAGE)
    fun activityFullLight() = activity("activity-full", dark = false) { assertWhole() }

    @Test
    @Config(qualifiers = FULL_PAGE)
    fun activityFullDark() = activity("activity-full", dark = true) { assertWhole() }

    @Test
    fun trafficLight() = traffic("traffic", dark = false)

    @Test
    fun trafficDark() = traffic("traffic", dark = true)

    @Test
    fun trafficScrubLight() = traffic("traffic-scrub", dark = false, scrub = SCRUB)

    @Test
    fun trafficScrubDark() = traffic("traffic-scrub", dark = true, scrub = SCRUB)

    private fun activity(screen: String, dark: Boolean, before: ComposeContentTestRule.() -> Unit = {}) =
        compose.rvShot(screen, dark, before) { RvActivityScreen(rvSampleActivity(), onAction = {}, onConnect = {}) }

    @Composable
    private fun Global() = RvActivityScreen(rvSampleActivity(), onAction = {}, onConnect = {}, mode = RvRouteMode.Global)

    private fun traffic(screen: String, dark: Boolean, scrub: Int? = null) =
        compose.rvShot(screen, dark) { RvTrafficScreen(rvSampleActivity(), RvData.session, onBack = {}, initialScrub = scrub) }

    /** Scrolls the page's content up by [dp], as a finger would, and lets the bottom bar finish collapsing. */
    private fun ComposeContentTestRule.scrollBy(dp: Float) {
        onNode(hasScrollAction()).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, dp * density.density) }
        waitForIdle()
    }

    private companion object {
        /** The scrolled golden moves the content this far, past the header, so the bottom bar collapses. */
        const val SCROLLED_DP = 400f

        /** A screen as tall as the whole 活動 page, for the full captures. */
        const val FULL_PAGE = "+h1951dp"

        /** The second the scrubbed golden holds a finger on: inside the sustained download. */
        const val SCRUB = 30
    }
}
