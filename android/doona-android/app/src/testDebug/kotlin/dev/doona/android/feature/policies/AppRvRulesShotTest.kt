package dev.doona.android.feature.policies

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RvPart
import dev.doona.android.prototype.rv.RV_FS200
import dev.doona.android.prototype.rv.Utc
import dev.doona.android.prototype.rv.assertWhole
import dev.doona.android.prototype.rv.rvContent
import dev.doona.android.prototype.rv.rvRowRules
import dev.doona.android.testing.drawFully
import dev.doona.android.vpn.FakeHonk
import dev.doona.android.vpn.HonkFixtures
import dev.doona.android.vpn.NativeApi
import dev.doona.android.vpn.PolicyHonkFixtures
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
 * 全部規則 as the app draws it: honk's native API, RulesViewModel, the mapper, then the page of the approved
 * `proto-rv-rules-*` samples (goldens `app-rv-rules-*`). On the RV sample profile ([PolicyHonkFixtures.rvSample]) it is every
 * rule honk runs, the whole page; with the groups named at length as users name them in honk by hand the targets wrap
 * as the row rules say; files honk did not load and a read that failed show the approved banners; before the first read
 * ends it is the skeleton. No place mapper is wired yet (P3c), so every node is 其他.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class AppRvRulesShotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    @Test
    @Config(qualifiers = WHOLE)
    fun rulesLight() = shot("rules", dark = false, whole = true) { Page({ PolicyHonkFixtures.rvSample() }) }

    @Test
    @Config(qualifiers = WHOLE)
    fun rulesDark() = shot("rules", dark = true, whole = true) { Page({ PolicyHonkFixtures.rvSample() }) }

    @Test
    @Config(qualifiers = WHOLE_FS200)
    fun rulesFs200() = shot("rules", dark = false, fontScale = RV_FS200, whole = true) { Page({ PolicyHonkFixtures.rvSample() }) }

    @Test
    @Config(qualifiers = WHOLE_EN)
    fun rulesEnLight() = shot("rules-en", dark = false, whole = true) { Page({ PolicyHonkFixtures.rvSample() }) }

    @Test
    @Config(qualifiers = WHOLE_LONG)
    fun longNamesLight() = shot("rules-long", dark = false, whole = true) { Page({ PolicyHonkFixtures.rvSample(long = true) }) }

    @Test
    @Config(qualifiers = WHOLE_LONG)
    fun longNamesDark() = shot("rules-long", dark = true, whole = true) { Page({ PolicyHonkFixtures.rvSample(long = true) }) }

    @Test
    fun parseFailedLight() = shot("rules-parse-failed", dark = false) { Page(::unloaded) }

    @Test
    fun parseFailedDark() = shot("rules-parse-failed", dark = true) { Page(::unloaded) }

    @Test
    fun readFailedLight() = shot("rules-read-failed", dark = false) { Page(::unreadable) }

    @Test
    fun readFailedDark() = shot("rules-read-failed", dark = true) { Page(::unreadable) }

    @Test
    fun loadingLight() = shot("rules-loading", dark = false) {
        val vm = remember { RulesViewModel(PolicyHonkFixtures.rvSample(), io = StandardTestDispatcher()) }
        RulesRoute(vm, onBack = {}, onTemplate = {})
    }

    /** A reread that failed (DS 1126i 2): the banner with 重試 over the rules last read, every row of them still drawn. */
    @Test
    @Config(qualifiers = WHOLE_REREAD)
    fun rereadFailedLight() {
        val honk = PolicyHonkFixtures.rvSample()
        val vm = RulesViewModel(honk, io = Dispatchers.Unconfined)
        var rows = 0
        val reread = {
            rows = compose.onAllNodes(hasTestTag(RvPart.ROW), useUnmergedTree = true).fetchSemanticsNodes().size
            unreadable(honk)
            vm.retry()
        }
        shot("rules-reread-failed", dark = false, whole = true, then = reread) { RulesRoute(vm, onBack = {}, onTemplate = {}) }
        assertEquals(1, shown(text(R.string.rv_rules_read_failed)))
        assertEquals(rows, compose.onAllNodes(hasTestTag(RvPart.ROW), useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(1, shown(text(R.string.rv_effective_open)))
    }

    /** A source that did not parse: the banner's 範本 opens the templates. */
    @Test
    fun theParseBannerOpensTheTemplates() {
        var opened = 0
        compose.rvContent(dark = false) { Page(::unloaded, onTemplate = { opened++ }) }
        compose.onNode(hasText(text(R.string.rv_policy_template)), useUnmergedTree = true).performClick()
        compose.waitForIdle()
        assertEquals(1, opened)
    }

    /** A failed read: 重試 reads the rules again, and they show. */
    @Test
    fun aFailedReadRetries() {
        val honk = unreadable()
        compose.rvContent(dark = false) { Page({ honk }) }
        assertEquals(1, shown(text(R.string.rv_rules_read_failed)))
        honk.on("GET", "/api/v1/rules", 200, HonkFixtures.body("rules/rv-sample"))
        compose.onNode(hasText(text(R.string.rv_retry)), useUnmergedTree = true).performClick()
        compose.waitForIdle()
        assertEquals(0, shown(text(R.string.rv_rules_read_failed)))
    }

    private fun shown(text: String) = compose.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().size

    private fun text(id: Int) = ApplicationProvider.getApplicationContext<Context>().getString(id)

    private fun shot(screen: String, dark: Boolean, fontScale: Float = 1f, whole: Boolean = false, then: () -> Unit = {}, content: @Composable () -> Unit) {
        compose.rvContent(dark, fontScale, content = content)
        then()
        compose.waitForIdle()
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

    /** The route on a view model reading on the test's thread. */
    @Composable
    private fun Page(api: () -> NativeApi, onTemplate: () -> Unit = {}) {
        val vm = remember { RulesViewModel(api(), io = Dispatchers.Unconfined) }
        RulesRoute(vm, onBack = {}, onTemplate = onTemplate)
    }

    /** The sample profile after a reload whose files honk did not load. */
    private fun unloaded(): FakeHonk = PolicyHonkFixtures.rvSample().apply {
        on("GET", "/api/v1/runtime", 200, HonkFixtures.body("runtime/runtime-reload-failed"))
    }

    /** The sample profile whose rules cannot be read now: honk has no coherent generation to read them from. */
    private fun unreadable(honk: FakeHonk = PolicyHonkFixtures.rvSample()): FakeHonk = honk.apply {
        on("GET", "/api/v1/rules", 503, HonkFixtures.body("errors/snapshot-unavailable"))
    }

    private companion object {
        val SHOTS: String = System.getProperty("doona.shots") ?: "src/test/screenshots"
        const val PERCENT = 100

        /** Windows as tall as each page whole; measured by assertWhole. */
        const val WHOLE = "+h1788dp"
        const val WHOLE_FS200 = "+h2658dp"
        const val WHOLE_EN = "en-w412dp-h1830dp-notnight-xhdpi"
        const val WHOLE_LONG = "+h1947dp"
        const val WHOLE_REREAD = "+h1913dp"
    }
}
