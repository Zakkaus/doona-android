package dev.doona.android.feature.policies

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.doona.android.R
import dev.doona.android.engine.Engine
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.Mode
import dev.doona.android.engine.fake.rvSampleEngine
import dev.doona.android.prototype.rv.RV_FS200
import dev.doona.android.prototype.rv.Utc
import dev.doona.android.prototype.rv.assertWhole
import dev.doona.android.prototype.rv.rvContent
import dev.doona.android.prototype.rv.rvRowRules
import dev.doona.android.testing.drawFully
import dev.doona.android.vpn.FakeHonk
import dev.doona.android.vpn.HonkFixtures
import dev.doona.android.vpn.PolicyHonkFixtures
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 策略 as the app draws it: honk's native API ([HonkFixtures]), PoliciesViewModel, the mapper, then the page (goldens
 * `app-rv-policy-*`); the mode is the sample engine's. On the RV sample in 規則 it is the page of `proto-rv-policy-*`, in
 * the same windows; before the first read ends it is the skeleton of `proto-rv-policy-loading-*`, and with no
 * subscription the empty state of `proto-rv-policy-empty-*`. The sample instant, 2026-11-22 10:24 UTC, is the page's now.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class AppRvPolicyShotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    @Test
    @Config(qualifiers = WHOLE_RULE)
    fun ruleLight() = shot("policy", dark = false, whole = true) { Page(PolicyHonkFixtures::rvSample) }

    @Test
    @Config(qualifiers = WHOLE_RULE)
    fun ruleDark() = shot("policy", dark = true, whole = true) { Page(PolicyHonkFixtures::rvSample) }

    @Test
    fun ruleFs200() = shot("policy", dark = false, fontScale = RV_FS200) { Page(PolicyHonkFixtures::rvSample) }

    @Test
    @Config(qualifiers = WHOLE_RULE_EN)
    fun ruleEnLight() = shot("policy-en", dark = false, whole = true) { Page(PolicyHonkFixtures::rvSample) }

    /**
     * Groups as a user writes them in honk by hand ([PolicyHonkFixtures.honkGroups]): no main group, nested groups, a fallback, an empty
     * group. Its rules lead with a REJECT (拒絕), and the sample's rules name groups this profile lacks (找不到群組).
     */
    @Test
    fun honkGroupsLight() = shot("policy-honk", dark = false) { Page(PolicyHonkFixtures::honkGroups) }

    /** The first read failed: the banner with 重試 in place of the cards, no skeleton left standing. */
    @Test
    fun readFailedLight() = shot("policy-read-failed", dark = false) { Page(::unreadable) }

    @Test
    fun loadingLight() = shot("policy-loading", dark = false) { Page(PolicyHonkFixtures::rvSample, io = StandardTestDispatcher()) }

    @Test
    fun loadingDark() = shot("policy-loading", dark = true) { Page(PolicyHonkFixtures::rvSample, io = StandardTestDispatcher()) }

    @Test
    fun emptyLight() = shot("policy-empty", dark = false) { Page(::noSubscription) }

    @Test
    fun emptyDark() = shot("policy-empty", dark = true) { Page(::noSubscription) }

    /** A failed mode switch leaves the library's banner at the top: what failed, the engine's reason and 重試 (plan 8). */
    @Test
    fun aFailedModeSwitchShowsItsBannerAndRetries() {
        var calls = 0
        var answer: Result<Unit> = Result.failure(EngineException(ErrorCode.Busy))
        val failing = object : Engine by rvSampleEngine() {
            override suspend fun setMode(mode: Mode): Result<Unit> {
                calls++
                return answer
            }
        }
        compose.rvContent(dark = false) { Page(PolicyHonkFixtures::rvSample, engine = failing) }
        compose.onAllNodes(hasText(text(R.string.rv_failed_switch_mode)), useUnmergedTree = true).fetchSemanticsNodes().let { assertEquals(0, it.size) }
        // The mode switch heads the page; the rules below it can also go 直連.
        compose.onAllNodes(hasText(text(R.string.mode_direct)), useUnmergedTree = true)[0].performClick()
        compose.waitForIdle()
        compose.onNode(hasText(text(R.string.rv_failed_switch_mode)), useUnmergedTree = true).assertExists()
        compose.onNode(hasText(text(R.string.error_busy)), useUnmergedTree = true).assertExists()
        answer = Result.success(Unit)
        compose.onNode(hasText(text(R.string.rv_retry)), useUnmergedTree = true).performClick()
        compose.waitForIdle()
        assertEquals(2, calls)
        compose.onAllNodes(hasText(text(R.string.rv_failed_switch_mode)), useUnmergedTree = true).fetchSemanticsNodes().let { assertEquals(0, it.size) }
    }

    /** The empty state's 新增訂閱 is the route's way to the subscriptions. */
    @Test
    fun theEmptyStateOpensTheSubscriptions() {
        var opened = 0
        compose.rvContent(dark = false) { Page(::noSubscription, onOpenSubscriptions = { opened++ }) }
        compose.onNode(hasText(text(R.string.rv_add_subscription)), useUnmergedTree = true).performClick()
        assertEquals(1, opened)
    }

    private fun text(id: Int) = ApplicationProvider.getApplicationContext<Context>().getString(id)

    private fun shot(screen: String, dark: Boolean, fontScale: Float = 1f, whole: Boolean = false, content: @Composable () -> Unit) {
        compose.rvContent(dark, fontScale, content = content)
        if (whole) compose.assertWhole()
        compose.drawFully()
        val variant = when {
            fontScale != 1f -> "fs${(fontScale * PERCENT).toInt()}"
            dark -> "dark"
            else -> "light"
        }
        val name = "app-rv-$screen-$variant"
        compose.rvRowRules(name)
        compose.onRoot().captureToImage().asAndroidBitmap().captureRoboImage("$SHOTS/$name.png")
    }

    /** The route on a view model with the sample's clock, reading [honk] on [io] (the test's thread by default). */
    @Composable
    private fun Page(
        honk: () -> FakeHonk,
        onOpenSubscriptions: () -> Unit = {},
        engine: Engine = rvSampleEngine(),
        io: CoroutineDispatcher = Dispatchers.Unconfined,
    ) {
        val vm = remember { PoliciesViewModel(engine, honk(), clock = { SAMPLE_NOW }, io = io) }
        PoliciesRoute(vm, onOpenGroup = {}, onOpenSubscriptions = onOpenSubscriptions)
    }

    /** The sample whose rules honk cannot read. */
    private fun unreadable(): FakeHonk = PolicyHonkFixtures.rvSample().apply {
        on("GET", "/api/v1/rules", 503, HonkFixtures.body("errors/snapshot-unavailable"))
    }

    /** The sample with no subscription: nothing to route. */
    private fun noSubscription(): FakeHonk = PolicyHonkFixtures.rvSample().apply {
        on("GET", "/api/v1/providers", 200, """{"providers": [], "next_cursor": null}""")
    }

    private companion object {
        val SHOTS: String = System.getProperty("doona.shots") ?: "src/test/screenshots"
        const val PERCENT = 100
        const val SAMPLE_NOW = 1_795_343_040_000L

        /** `proto-rv-policy-{light,dark}`'s window, as tall as the page whole. */
        const val WHOLE_RULE = "+h1990dp"
        const val WHOLE_RULE_EN = "en-w412dp-h2200dp-notnight-xhdpi"
    }
}
