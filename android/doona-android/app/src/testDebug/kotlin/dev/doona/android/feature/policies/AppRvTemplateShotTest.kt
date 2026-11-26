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
import dev.doona.android.engine.Catalog
import dev.doona.android.engine.EffectiveConfig
import dev.doona.android.engine.Engine
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.PerApp
import dev.doona.android.engine.RuleOptions
import dev.doona.android.engine.RuleTemplate
import dev.doona.android.engine.fake.FakeEngine
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 範本, its review and 生效設定 as the app draws them from an engine (goldens `app-rv-template-*`,
 * `app-rv-template-impact-*`, `app-rv-effective-config-*`). 範本 lists the sample engine's five templates, 繞過中國大陸 in
 * use and GFW waiting for GeoSite data; the review opens from rules that come from no template, as [handWritten]'s do.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class AppRvTemplateShotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    @Test
    fun templateLight() = shot("template", dark = false) { Templates() }

    @Test
    fun templateDark() = shot("template", dark = true, whole = true) { Templates() }

    @Test
    @Config(qualifiers = WHOLE_TEMPLATE_FS200)
    fun templateFs200() = shot("template", dark = false, fontScale = RV_FS200, whole = true) {
        Templates()
    }

    @Test
    @Config(qualifiers = WHOLE_TEMPLATE_EN)
    fun templateEnLight() = shot("template-en", dark = false, whole = true) { Templates() }

    @Test
    fun templateHandWrittenLight() = shot("template-hand", dark = false) { Templates(PolicyHonkFixtures.handWritten()) }

    @Test
    fun templateReadFailedLight() = shot("template-read-failed", dark = false) { Templates(unreadable()) }

    @Test
    fun impactLight() = shot("template-impact", dark = false) { Impact() }

    /** Before honk's config is read (a back stack restored after the process died, DS 1126i 1): the review's skeleton. */
    @Test
    fun impactLoadingLight() = shot("template-impact-loading", dark = false) { Impact(io = StandardTestDispatcher()) }

    @Test
    fun impactLoadingDark() = shot("template-impact-loading", dark = true) { Impact(io = StandardTestDispatcher()) }

    @Test
    fun impactLoadingFs200() = shot("template-impact-loading", dark = false, fontScale = RV_FS200) {
        Impact(io = StandardTestDispatcher())
    }

    @Test
    fun impactDark() = shot("template-impact", dark = true) { Impact() }

    @Test
    @Config(qualifiers = WHOLE_IMPACT_FS200)
    fun impactFs200() = shot("template-impact", dark = false, fontScale = RV_FS200, whole = true) {
        Impact()
    }

    @Test
    @Config(qualifiers = WHOLE_IMPACT_EN)
    fun impactEnLight() = shot("template-impact-en", dark = false, whole = true) {
        Impact()
    }

    @Test
    @Config(qualifiers = WHOLE_EFFECTIVE)
    fun effectiveLight() = shot("effective-config", dark = false, whole = true) { Effective() }

    @Test
    @Config(qualifiers = WHOLE_EFFECTIVE)
    fun effectiveDark() = shot("effective-config", dark = true, whole = true) { Effective() }

    @Test
    @Config(qualifiers = WHOLE_EFFECTIVE_FS200)
    fun effectiveFs200() = shot("effective-config", dark = false, fontScale = RV_FS200, whole = true) { Effective() }

    @Test
    @Config(qualifiers = WHOLE_EFFECTIVE_EN)
    fun effectiveEnLight() = shot("effective-config-en", dark = false, whole = true) { Effective() }

    /** A read after the first failed: the banner with 重試 over the configuration last read (DS 4). */
    @Test
    @Config(qualifiers = WHOLE_EFFECTIVE_REREAD)
    fun effectiveRereadFailedLight() {
        val honk = PolicyHonkFixtures.rvSample()
        lateinit var vm: EffectiveConfigViewModel
        val reread = {
            honk.on("GET", "/api/v1/rules", 503, HonkFixtures.body("errors/snapshot-unavailable"))
            vm.retry()
        }
        shot("effective-config-reread-failed", dark = false, whole = true, then = reread) {
            Effective(honk) { vm = it }
        }
    }

    /** With the template in use 檢視變更 stays off; from a hand-written file it opens the review of the first template. */
    @Test
    fun theReviewOpensOnlyForAnotherTemplate() {
        val opened = mutableListOf<String>()
        compose.rvContent(dark = false) { Templates(PolicyHonkFixtures.handWritten(), onReview = { opened += it }) }
        compose.onNode(hasText(text(R.string.rv_template_review)), useUnmergedTree = true).performClick()
        assertEquals(listOf("cn-direct"), opened)
    }

    /** 套用 writes the template into honk's file, then leaves for 策略. */
    @Test
    fun applyingSetsTheTemplateAndLeaves() {
        val honk = applying()
        val io = StandardTestDispatcher()
        var left = 0
        compose.rvContent(dark = false) { Impact(honk, io = io, onApplied = { left++ }) }
        io.scheduler.advanceUntilIdle()
        compose.waitForIdle()
        compose.onNode(hasText(text(R.string.rv_policy_apply)), useUnmergedTree = true).performClick()
        io.scheduler.advanceUntilIdle()
        compose.waitForIdle()
        assertEquals(listOf("PUT /api/v1/config/sources/source-main"), puts(honk))
        assertEquals(1, left)
    }

    /**
     * Before honk's config is read 套用 does nothing and the line on new connections waits; once it is, the review shows
     * with that line, and 套用 writes the template.
     */
    @Test
    fun theReviewWaitsForTheCatalog() {
        val honk = applying()
        val io = StandardTestDispatcher()
        var left = 0
        compose.rvContent(dark = false) { Impact(honk, io = io, onApplied = { left++ }) }
        val apply = compose.onNode(hasText(text(R.string.rv_policy_apply)), useUnmergedTree = true)
        val footnote = compose.onNode(hasText(text(R.string.rv_template_new_flows)), useUnmergedTree = true)
        footnote.assertDoesNotExist()
        apply.performClick()
        compose.waitForIdle()
        assertEquals(emptyList<String>(), puts(honk))
        io.scheduler.advanceUntilIdle()
        compose.waitForIdle()
        footnote.assertExists()
        apply.performClick()
        io.scheduler.advanceUntilIdle()
        compose.waitForIdle()
        assertEquals(listOf("PUT /api/v1/config/sources/source-main"), puts(honk))
        assertEquals(1, left)
    }

    /** 範本 on honk serving [honk], reading on [io] (the test's thread unless given). */
    @Composable
    private fun Templates(honk: FakeHonk = PolicyHonkFixtures.rvSample(), onReview: (String) -> Unit = {}) {
        TemplateRoute(remember { TemplateViewModel(honk, io = Dispatchers.Unconfined) }, onBack = {}, onReview = onReview)
    }

    /** The review of cn-direct over the hand-written sample, reading on [io]. */
    @Composable
    private fun Impact(honk: FakeHonk = PolicyHonkFixtures.handWritten(), io: CoroutineDispatcher = Dispatchers.Unconfined, onApplied: () -> Unit = {}) {
        TemplateImpactRoute(remember { TemplateImpactViewModel(honk, "cn-direct", io) }, onBack = {}, onApplied = onApplied)
    }

    /** The hand-written sample, honk accepting a new file and reloading it. */
    private fun applying(): FakeHonk = PolicyHonkFixtures.handWritten().apply {
        on("PUT", "/api/v1/config/sources/source-main", 202, HonkFixtures.body("config-replace/queued"))
        on("GET", "/api/v1/operations/op-config-01", 200, HonkFixtures.body("operations/reload-complete"))
    }

    private fun puts(honk: FakeHonk) = honk.calls.filter { it.method == "PUT" }.map { "${it.method} ${it.path}" }

    /** The sample whose config honk cannot read. */
    private fun unreadable(): FakeHonk = PolicyHonkFixtures.rvSample().apply {
        on("GET", "/api/v1/config", 503, HonkFixtures.body("errors/snapshot-unavailable"))
    }

    /** The route on honk serving [honk], the mode the sample engine's, reading on the test's thread; [made] gets the view model. */
    @Composable
    private fun Effective(honk: FakeHonk = PolicyHonkFixtures.rvSample(), made: (EffectiveConfigViewModel) -> Unit = {}) {
        val vm = remember { EffectiveConfigViewModel(rvSampleEngine(), honk, io = Dispatchers.Unconfined).also(made) }
        EffectiveConfigRoute(vm, onBack = {}, onRules = {})
    }

    private fun text(id: Int) = ApplicationProvider.getApplicationContext<Context>().getString(id)

    private fun shot(
        screen: String,
        dark: Boolean,
        fontScale: Float = 1f,
        whole: Boolean = false,
        then: () -> Unit = {},
        content: @Composable () -> Unit,
    ) {
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

    private companion object {
        val SHOTS: String = System.getProperty("doona.shots") ?: "src/test/screenshots"

        /** Taller than 生效設定 whole, as `proto-rv-effective-config-*`'s window (+h2106dp) and the app's longer rules need. */
        const val WHOLE_EFFECTIVE = "+h2600dp"
        const val WHOLE_EFFECTIVE_REREAD = "+h2600dp"

        /** The other pages' windows as tall as each page whole; measured by assertWhole. */
        const val WHOLE_TEMPLATE_FS200 = "+h1109dp"
        const val WHOLE_TEMPLATE_EN = "en-w412dp-h915dp-notnight-xhdpi"
        const val WHOLE_IMPACT_FS200 = "+h915dp"
        const val WHOLE_IMPACT_EN = "en-w412dp-h915dp-notnight-xhdpi"
        const val WHOLE_EFFECTIVE_FS200 = "+h3326dp"
        const val WHOLE_EFFECTIVE_EN = "en-w412dp-h2600dp-notnight-xhdpi"
        const val PERCENT = 100
    }
}
