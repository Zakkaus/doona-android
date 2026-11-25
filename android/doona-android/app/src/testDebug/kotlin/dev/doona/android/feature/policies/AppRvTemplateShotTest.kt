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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
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
    fun templateLight() = shot("template", dark = false) { TemplateRoute(rvSampleEngine(), onBack = {}, onReview = {}) }

    @Test
    fun templateDark() = shot("template", dark = true, whole = true) { TemplateRoute(rvSampleEngine(), onBack = {}, onReview = {}) }

    @Test
    @Config(qualifiers = WHOLE_TEMPLATE_FS200)
    fun templateFs200() = shot("template", dark = false, fontScale = RV_FS200, whole = true) {
        TemplateRoute(rvSampleEngine(), onBack = {}, onReview = {})
    }

    @Test
    @Config(qualifiers = WHOLE_TEMPLATE_EN)
    fun templateEnLight() = shot("template-en", dark = false, whole = true) { TemplateRoute(rvSampleEngine(), onBack = {}, onReview = {}) }

    @Test
    fun templateHandWrittenLight() = shot("template-hand", dark = false) { TemplateRoute(handWritten(), onBack = {}, onReview = {}) }

    @Test
    fun templateReadFailedLight() = shot("template-read-failed", dark = false) { TemplateRoute(Unlisted(), onBack = {}, onReview = {}) }

    @Test
    fun impactLight() = shot("template-impact", dark = false) { TemplateImpactRoute(handWritten(), "cn-direct", onBack = {}, onApplied = {}) }

    /** Before the catalog is read (a back stack restored after the process died, DS 1126i 1): the review's skeleton. */
    @Test
    fun impactLoadingLight() = shot("template-impact-loading", dark = false) { TemplateImpactRoute(Late(), "cn-direct", onBack = {}, onApplied = {}) }

    @Test
    fun impactLoadingDark() = shot("template-impact-loading", dark = true) { TemplateImpactRoute(Late(), "cn-direct", onBack = {}, onApplied = {}) }

    @Test
    fun impactLoadingFs200() = shot("template-impact-loading", dark = false, fontScale = RV_FS200) {
        TemplateImpactRoute(Late(), "cn-direct", onBack = {}, onApplied = {})
    }

    @Test
    fun impactDark() = shot("template-impact", dark = true) { TemplateImpactRoute(handWritten(), "cn-direct", onBack = {}, onApplied = {}) }

    @Test
    @Config(qualifiers = WHOLE_IMPACT_FS200)
    fun impactFs200() = shot("template-impact", dark = false, fontScale = RV_FS200, whole = true) {
        TemplateImpactRoute(handWritten(), "cn-direct", onBack = {}, onApplied = {})
    }

    @Test
    @Config(qualifiers = WHOLE_IMPACT_EN)
    fun impactEnLight() = shot("template-impact-en", dark = false, whole = true) {
        TemplateImpactRoute(handWritten(), "cn-direct", onBack = {}, onApplied = {})
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
        val engine = Rereading()
        shot("effective-config-reread-failed", dark = false, whole = true, then = { runBlocking { engine.setPerApp(PerApp(packages = setOf("a"))) } }) {
            Effective { engine }
        }
    }

    /** With the template in use 檢視變更 stays off; from a hand-written file it opens the review of the first template. */
    @Test
    fun theReviewOpensOnlyForAnotherTemplate() {
        val opened = mutableListOf<String>()
        compose.rvContent(dark = false) { TemplateRoute(handWritten(), onBack = {}, onReview = { opened += it }) }
        compose.onNode(hasText(text(R.string.rv_template_review)), useUnmergedTree = true).performClick()
        assertEquals(listOf("cn-direct"), opened)
    }

    /** 套用 sets the template, then leaves for 策略. */
    @Test
    fun applyingSetsTheTemplateAndLeaves() {
        val engine = handWritten()
        var left = 0
        compose.rvContent(dark = false) { TemplateImpactRoute(engine, "cn-direct", onBack = {}, onApplied = { left++ }) }
        compose.onNode(hasText(text(R.string.rv_policy_apply)), useUnmergedTree = true).performClick()
        compose.waitForIdle()
        assertEquals(listOf("cn-direct"), engine.applied)
        assertEquals(1, left)
    }

    /**
     * Before the catalog is read 套用 does nothing and the line on new connections waits; once it is, the review shows
     * with that line, and 套用 sets the template.
     */
    @Test
    fun theReviewWaitsForTheCatalog() {
        val engine = Late()
        var left = 0
        compose.rvContent(dark = false) { TemplateImpactRoute(engine, "cn-direct", onBack = {}, onApplied = { left++ }) }
        val apply = compose.onNode(hasText(text(R.string.rv_policy_apply)), useUnmergedTree = true)
        val footnote = compose.onNode(hasText(text(R.string.rv_template_new_flows)), useUnmergedTree = true)
        footnote.assertDoesNotExist()
        apply.performClick()
        compose.waitForIdle()
        assertEquals(emptyList<String>(), engine.applied)
        engine.read.value = engine.loaded
        compose.waitForIdle()
        footnote.assertExists()
        apply.performClick()
        compose.waitForIdle()
        assertEquals(listOf("cn-direct"), engine.applied)
        assertEquals(1, left)
    }

    @Composable
    private fun Effective(make: () -> Engine = ::rvSampleEngine) {
        val engine = remember { make() }
        val vm = remember { EffectiveConfigViewModel(engine, reads = Dispatchers.Unconfined) }
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

    /** The sample engine whose configuration reads once, then fails with Busy. */
    private class Rereading(private val base: FakeEngine = rvSampleEngine()) : Engine by base {
        private var reads = 0

        override suspend fun effectiveConfig(): Result<EffectiveConfig> =
            if (reads++ == 0) base.effectiveConfig() else Result.failure(EngineException(ErrorCode.Busy))
    }

    /** The sample engine with its rules from the configuration file, no template; it records the templates set. */
    private class HandWritten(private val base: Engine = rvSampleEngine()) : Engine by base {
        val applied = mutableListOf<String>()

        override val catalog: StateFlow<Catalog?> = base.catalog
            .map { it?.copy(ruleSource = it.ruleSource.copy(template = null, subscriptionId = null)) }
            .stateIn(CoroutineScope(Dispatchers.Unconfined), SharingStarted.Eagerly, null)

        override suspend fun setRuleSource(template: String, options: RuleOptions): Result<Unit> {
            applied += template
            return Result.success(Unit)
        }
    }

    private fun handWritten() = HandWritten()

    /** [HandWritten] whose catalog is unread until the test sets [read] to [loaded]. */
    private class Late(private val base: HandWritten = HandWritten()) : Engine by base {
        val applied get() = base.applied
        val loaded: Catalog? = base.catalog.value
        val read = MutableStateFlow<Catalog?>(null)

        override val catalog: StateFlow<Catalog?> = read
    }

    /** The sample engine, its template list unreadable. */
    private class Unlisted(private val base: Engine = rvSampleEngine()) : Engine by base {
        override suspend fun ruleTemplates(): Result<List<RuleTemplate>> = Result.failure(EngineException(ErrorCode.Internal))
    }

    private companion object {
        val SHOTS: String = System.getProperty("doona.shots") ?: "src/test/screenshots"

        /** Taller than 生效設定 whole, as `proto-rv-effective-config-*`'s window (+h2106dp) and the app's longer rules need. */
        const val WHOLE_EFFECTIVE = "+h2600dp"
        const val WHOLE_EFFECTIVE_REREAD = "+h2600dp"

        /** The other pages' windows as tall as each page whole; measured by assertWhole. */
        const val WHOLE_TEMPLATE_FS200 = "+h1099dp"
        const val WHOLE_TEMPLATE_EN = "en-w412dp-h915dp-notnight-xhdpi"
        const val WHOLE_IMPACT_FS200 = "+h915dp"
        const val WHOLE_IMPACT_EN = "en-w412dp-h915dp-notnight-xhdpi"
        const val WHOLE_EFFECTIVE_FS200 = "+h3310dp"
        const val WHOLE_EFFECTIVE_EN = "en-w412dp-h2600dp-notnight-xhdpi"
        const val PERCENT = 100
    }
}
