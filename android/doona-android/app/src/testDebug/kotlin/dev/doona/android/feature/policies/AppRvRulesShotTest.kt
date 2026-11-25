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
import dev.doona.android.engine.Catalog
import dev.doona.android.engine.Engine
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.RulePage
import dev.doona.android.engine.fake.FakeEngine
import dev.doona.android.engine.fake.rvSampleEngine
import dev.doona.android.prototype.rv.RV_FS200
import dev.doona.android.prototype.rv.Utc
import dev.doona.android.prototype.rv.assertWhole
import dev.doona.android.prototype.rv.rvContent
import dev.doona.android.prototype.rv.rvRowRules
import dev.doona.android.testing.drawFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 全部規則 as the app draws it: an engine, RulesViewModel, the mapper, then the page of the approved `proto-rv-rules-*`
 * samples (goldens `app-rv-rules-*`). On the RV sample engine it is every rule of the sample's source with today's bytes,
 * the whole page; with the groups named at length as users name them in honk by hand ([longNames]) the targets wrap as
 * the row rules say; a source that did not parse and a read that failed show the approved banners; before the engine is
 * ready it is the skeleton. No place mapper is wired yet (P3c), so every node is 其他.
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
    fun rulesLight() = shot("rules", dark = false, whole = true) { Page(::rvSampleEngine) }

    @Test
    @Config(qualifiers = WHOLE)
    fun rulesDark() = shot("rules", dark = true, whole = true) { Page(::rvSampleEngine) }

    @Test
    @Config(qualifiers = WHOLE_FS200)
    fun rulesFs200() = shot("rules", dark = false, fontScale = RV_FS200, whole = true) { Page(::rvSampleEngine) }

    @Test
    @Config(qualifiers = WHOLE_EN)
    fun rulesEnLight() = shot("rules-en", dark = false, whole = true) { Page(::rvSampleEngine) }

    @Test
    @Config(qualifiers = WHOLE_LONG)
    fun longNamesLight() = shot("rules-long", dark = false, whole = true) { Page(::longNames) }

    @Test
    @Config(qualifiers = WHOLE_LONG)
    fun longNamesDark() = shot("rules-long", dark = true, whole = true) { Page(::longNames) }

    @Test
    fun parseFailedLight() = shot("rules-parse-failed", dark = false) { Page(::unparsed) }

    @Test
    fun parseFailedDark() = shot("rules-parse-failed", dark = true) { Page(::unparsed) }

    @Test
    fun readFailedLight() = shot("rules-read-failed", dark = false) { Page(::unreadable) }

    @Test
    fun readFailedDark() = shot("rules-read-failed", dark = true) { Page(::unreadable) }

    @Test
    fun loadingLight() = shot("rules-loading", dark = false) { Page(::notReady) }

    /** A reread that failed (DS 1126i 2): the banner with 重試 over the rules last read, every row of them still drawn. */
    @Test
    @Config(qualifiers = WHOLE_REREAD)
    fun rereadFailedLight() {
        val engine = RereadingRules()
        val vm = RulesViewModel(engine, reads = Dispatchers.Unconfined)
        var rows = 0
        val reread = {
            rows = compose.onAllNodes(hasTestTag(RvPart.ROW), useUnmergedTree = true).fetchSemanticsNodes().size
            engine.fail = true
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
        compose.rvContent(dark = false) { Page(::unparsed, onTemplate = { opened++ }) }
        compose.onNode(hasText(text(R.string.rv_policy_template)), useUnmergedTree = true).performClick()
        compose.waitForIdle()
        assertEquals(1, opened)
    }

    /** A failed read: 重試 reads the rules again, and they show. */
    @Test
    fun aFailedReadRetries() {
        var fail = true
        val sample = rvSampleEngine()
        val engine = object : Engine by sample {
            override suspend fun rules(offset: Int, limit: Int): Result<RulePage> =
                if (fail) Result.failure(EngineException(ErrorCode.Busy)) else sample.rules(offset, limit)
        }
        compose.rvContent(dark = false) { Page({ engine }) }
        assertEquals(1, shown(text(R.string.rv_rules_read_failed)))
        fail = false
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
    private fun Page(engine: () -> Engine, onTemplate: () -> Unit = {}) {
        val vm = remember { RulesViewModel(engine(), reads = Dispatchers.Unconfined) }
        RulesRoute(vm, onBack = {}, onTemplate = onTemplate)
    }

    /** The sample engine with its rules' groups named at length, zh and en mixed, as users write them in honk. */
    private fun longNames(): FakeEngine = rvSampleEngine().apply {
        groups.value = groups.value.map { group -> LONG_NAMES[group.id]?.let { group.copy(name = it) } ?: group }
    }

    /** The sample engine whose rule source did not parse. */
    private fun unparsed(): Engine {
        val sample = rvSampleEngine()
        val catalog = sample.catalog.value!!
        return object : Engine by sample {
            override val catalog: StateFlow<Catalog?> = MutableStateFlow(catalog.copy(ruleSource = catalog.ruleSource.copy(error = ErrorCode.ParseFailed)))
        }
    }

    /** The sample engine whose rules cannot be read. */
    private fun unreadable(): Engine = object : Engine by rvSampleEngine() {
        override suspend fun rules(offset: Int, limit: Int): Result<RulePage> = Result.failure(EngineException(ErrorCode.Busy))
    }

    /** The sample engine whose rules read until [fail] is set, then fail with Busy. */
    private class RereadingRules(private val base: Engine = rvSampleEngine()) : Engine by base {
        var fail = false

        override suspend fun rules(offset: Int, limit: Int): Result<RulePage> =
            if (fail) Result.failure(EngineException(ErrorCode.Busy)) else base.rules(offset, limit)
    }

    /** The sample engine before its catalog has loaded: the page waits as a skeleton. */
    private fun notReady(): Engine = object : Engine by rvSampleEngine() {
        override val catalog: StateFlow<Catalog?> = MutableStateFlow(null)
    }

    private companion object {
        val SHOTS: String = System.getProperty("doona.shots") ?: "src/test/screenshots"
        const val PERCENT = 100

        val LONG_NAMES = mapOf(
            "g-Streaming" to "影音串流與直播平台（香港、臺灣、日本）",
            "g-AI" to "AI Services and Developer Tools (US West)",
            "g-JP" to "日本遊戲低延遲專線 Japan Gaming Relay",
        )

        /** Windows as tall as each page whole; measured by assertWhole. */
        const val WHOLE = "+h1728dp"
        const val WHOLE_FS200 = "+h2400dp"
        const val WHOLE_EN = "en-w412dp-h1752dp-notnight-xhdpi"
        const val WHOLE_LONG = "+h1881dp"
        const val WHOLE_REREAD = "+h1900dp"
    }
}
