package dev.doona.android.feature.traffic

import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.takahirom.roborazzi.captureRoboImage
import dev.doona.android.R
import dev.doona.android.prototype.rv.RV_EN
import dev.doona.android.prototype.rv.RV_FS115
import dev.doona.android.prototype.rv.RV_FS200
import dev.doona.android.prototype.rv.RvTrafficSample
import dev.doona.android.prototype.rv.Utc
import dev.doona.android.prototype.rv.assertWhole
import dev.doona.android.prototype.rv.data.RvData
import dev.doona.android.prototype.rv.rvContent
import dev.doona.android.prototype.rv.rvRowRules
import dev.doona.android.prototype.rv.rvSampleActivity
import dev.doona.android.prototype.rv.rvShot
import dev.doona.android.prototype.rv.rvText
import dev.doona.android.testing.drawFully
import dev.doona.android.vpn.honk.NativeRuntime
import dev.doona.android.vpn.sampleHonk
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

/**
 * 流量詳情 as the app draws it: rvSampleEngine's traffic as honk serves it ([sampleHonk]) through [TrafficViewModel] to the page, at the sample's 10:24 UTC.
 * Goldens `app-rv-traffic-*` beside the prototype's `proto-rv-traffic-*`: the live minute in light, dark, fs115, fs200
 * and English, scrubbed, the 10 分 window, a stopped tunnel and an engine whose reads fail; and the whole page in
 * light, dark, fs200 and English for the review panels, with the prototype's whole page where it has none.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class TrafficRvShotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    @Test fun trafficLight() = shot("traffic")

    @Test fun trafficDark() = shot("traffic", dark = true)

    @Test fun trafficFs115() = shot("traffic", fontScale = RV_FS115)

    @Test fun trafficFs200() = shot("traffic", fontScale = RV_FS200)

    @Test
    @Config(qualifiers = RV_EN)
    fun trafficEn() = shot("traffic-en")

    @Test fun scrubLight() = shot("traffic-scrub", scrub = SCRUB)

    @Test fun scrubDark() = shot("traffic-scrub", dark = true, scrub = SCRUB)

    /** 10 分: the 600 s honk keeps, drawn as 60 points of ten seconds, with the sample's peak 400 s ago. */
    @Test
    fun tenMinutesLight() = shot("traffic-10m") { compose.onNodeWithText(rvText(R.string.rv_range_10m)).performClick() }

    /** Stopped: the minute the core kept, no history of the app's own, and no session in the card. */
    @Test
    fun stoppedLight() = shot("traffic-stopped", running = false)

    /** Stopped, and every read fails: no chart is drawn, only its grid, and the card has no figures. */
    @Test
    fun emptyLight() = shot("traffic-empty", running = false, failing = true)

    @Test
    @Config(qualifiers = FULL_PAGE)
    fun fullLight() = shot("traffic-full", whole = true)

    @Test
    @Config(qualifiers = FULL_PAGE)
    fun fullDark() = shot("traffic-full", dark = true, whole = true)

    @Test
    @Config(qualifiers = FULL_PAGE_FS200)
    fun fullFs200() = shot("traffic-full", fontScale = RV_FS200, whole = true)

    @Test
    @Config(qualifiers = FULL_PAGE_EN)
    fun fullEn() = shot("traffic-full-en", whole = true)

    /** The prototype's whole page, which `proto-rv-traffic-*` (phone height) lacks: the approved side of the panels. */
    @Test
    @Config(qualifiers = FULL_PAGE)
    fun prototypeFullLight() = prototypeFull("traffic-full", dark = false)

    @Test
    @Config(qualifiers = FULL_PAGE)
    fun prototypeFullDark() = prototypeFull("traffic-full", dark = true)

    @Test
    @Config(qualifiers = FULL_PAGE_FS200)
    fun prototypeFullFs200() = prototypeFull("traffic-full", dark = false, fontScale = RV_FS200)

    @Test
    @Config(qualifiers = FULL_PAGE_EN)
    fun prototypeFullEn() = prototypeFull("traffic-full-en", dark = false)

    private fun prototypeFull(screen: String, dark: Boolean, fontScale: Float = 1f) =
        compose.rvShot(screen, dark, fontScale = fontScale, before = { assertWhole() }) { RvTrafficSample(rvSampleActivity(), RvData.session, onBack = {}) }

    private fun shot(
        screen: String,
        dark: Boolean = false,
        fontScale: Float = 1f,
        scrub: Int? = null,
        whole: Boolean = false,
        running: Boolean = true,
        failing: Boolean = false,
        then: () -> Unit = {},
    ) {
        val model = TrafficViewModel(NativeRuntime(sampleHonk(running, failing)), { RvData.nowEpochMs }, io = Dispatchers.Unconfined)
        compose.rvContent(dark, fontScale) {
            val state by model.ui.collectAsStateWithLifecycle()
            RvTrafficScreen(state, onRange = model::onRange, onBack = {}, initialScrub = scrub)
        }
        then()
        compose.drawFully()
        if (whole) compose.assertWhole()
        val variant = when {
            fontScale != 1f -> "fs${(fontScale * PERCENT).roundToInt()}"
            dark -> "dark"
            else -> "light"
        }
        val name = "app-rv-$screen-$variant"
        compose.rvRowRules(name)
        compose.onRoot().captureToImage().asAndroidBitmap().captureRoboImage("$SHOTS/$name.png")
    }

    private companion object {
        const val PERCENT = 100

        /** The prototype's scrubbed golden's point. */
        const val SCRUB = 30

        /** Screens as tall as the whole page: 56 dp past the phone's 915 at 1.0, 120.5 at 2.0, 56.5 in English. */
        const val FULL_PAGE = "+h971dp"
        const val FULL_PAGE_FS200 = "+h1036dp"
        const val FULL_PAGE_EN = "en-w412dp-h972dp-notnight-xhdpi"
        val SHOTS: String = System.getProperty("doona.shots") ?: "src/test/screenshots"
    }
}
