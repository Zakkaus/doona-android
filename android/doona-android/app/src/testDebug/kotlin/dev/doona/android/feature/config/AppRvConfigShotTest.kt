package dev.doona.android.feature.config

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.input.TextFieldValue
import androidx.navigation3.runtime.NavKey
import androidx.test.core.app.ApplicationProvider
import dev.doona.android.app.ConfigSourceKey
import dev.doona.android.app.DoonaApplication
import dev.doona.android.app.RvNavHost
import dev.doona.android.app.configEntries
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.engine.fake.RvSampleConfig
import dev.doona.android.honk.FakeHonk
import dev.doona.android.honk.HonkFixtures
import dev.doona.android.honk.PolicyHonkFixtures
import dev.doona.android.prototype.rv.RV_EN
import dev.doona.android.prototype.rv.Seed
import dev.doona.android.prototype.rv.Utc
import dev.doona.android.prototype.rv.assertWhole
import dev.doona.android.prototype.rv.data.RV_NOW
import dev.doona.android.prototype.rv.rvContent
import dev.doona.android.prototype.rv.rvShot
import dev.doona.android.prototype.rv.seedColorScheme
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The configuration pages as the app draws them: honk's native API answering with the sample tree's honk-shaped JSON
 * (PolicyHonkFixtures.configTree), the page's view model, the mapper, then the page (goldens `app-rv-config-*`). The
 * editor and its diffs are rules/cn.dae with 影音 sent to `media` and the wiki rule added; the stale diff has another
 * write of the file land first; the revision is 8; the imports are a pick past honk's check budget and one within it.
 * Where honk's data differs from the old sample engine's (no skipped records, includes under the entry, one file per
 * write, revisions by hash without names or text), the goldens moved with it: dt/k3-panels/REASONS.md.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class AppRvConfigShotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    @Test
    @Config(qualifiers = WHOLE_OVERVIEW_EN)
    fun overviewOpensTheTemplateFormAndReturns() {
        val template = mutableStateOf(false)
        val api = PolicyHonkFixtures.configTree()
        compose.setContent {
            RvTheme(seedColorScheme(Seed.Cyan.argb, false), false) {
                if (template.value) {
                    ConfigTemplateRoute(api) { template.value = false }
                } else {
                    val vm = remember { ConfigOverviewViewModel(api, now = { RV_NOW }, reads = Dispatchers.Unconfined) }
                    ConfigOverviewRoute(vm, onBack = {}, ConfigRoutes(template = { template.value = true }))
                }
            }
        }
        compose.onNodeWithText("Template").performScrollTo().performClick()
        compose.onNodeWithText("Global proxy", ignoreCase = true).assertIsNotEnabled()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Template").assertExists()
    }

    @Test
    fun restoreReturnsToTheNewActiveRevision() {
        val honk = PolicyHonkFixtures.configTree()
        val history = ConfigHistoryViewModel(honk, reads = Dispatchers.Unconfined)
        val revision = ConfigRevisionViewModel(honk, UNNAMED, reads = Dispatchers.Unconfined)
        var detail by mutableStateOf(false)
        compose.rvContent(dark = false) {
            if (detail) {
                ConfigRevisionRoute(revision, onBack = {}, onRestored = { detail = false })
            } else {
                ConfigHistoryRoute(history, onBack = {}, routes = ConfigRoutes(revision = { detail = true }))
            }
        }
        compose.onNodeWithText("第 8 版").performScrollTo().performClick()
        val updated = HonkFixtures.body("revisions/rv-config").replace("\"active\": 9", "\"active\": 10").replace("\"revision\": 9", "\"revision\": 10")
        honk.on("GET", "/api/v1/x-honk/config/revisions", 503, HonkFixtures.body("errors/snapshot-unavailable"))
        compose.onNodeWithText("復原").performClick()
        compose.waitUntil(5_000) { history.uiState.value.failure != null }
        compose.onNodeWithText("無法讀取設定檔").assertExists()
        honk.on("GET", "/api/v1/x-honk/config/revisions", 200, updated)
        compose.onNodeWithText("重試").performClick()
        compose.waitUntil(5_000) { history.uiState.value.value?.headRev == 10 }
        compose.onNodeWithText("第 10 版").assertExists()
        compose.onNodeWithText("使用中").assertExists()
        assertEquals(1, honk.calls.count { it.path.endsWith("/8/activate") })
    }

    @Test
    fun removedLineOpensItsReplacementInTheDraft() {
        val vm = ConfigSourceViewModel(PolicyHonkFixtures.configTree(), RvConfigSourceData.CN, reads = Dispatchers.Unconfined)
        vm.onValueChange(TextFieldValue(CLEAN))
        vm.review()
        compose.rvContent(dark = false) { ConfigSourceRoute(vm, onBack = {}, onApplied = {}, onFile = {}) }
        compose.onNodeWithContentDescription("移除，第 7 行：  domain(keyword: 影音) -> proxy").performScrollTo().performClick()
        compose.onNodeWithContentDescription(RvConfigSourceData.CN).assertExists()
        val value = vm.uiState.value.value
        assertEquals("  domain(keyword: 影音) -> media", value.text.substring(value.selection.min, value.selection.max))
    }

    @Test
    fun switchingFilesKeepsTheSearchableDraft() {
        val api = ApplicationProvider.getApplicationContext<DoonaApplication>().container.nativeApi as FakeHonk
        api.on("GET", "/api/v1/config", 200, HonkFixtures.body("config/rv-config"))
        api.on("POST", "/api/v1/config/validate", 200, HonkFixtures.body("config-validate/valid"))
        api.on("PUT", "/api/v1/config/sources/source-f4", 202, HonkFixtures.body("config-replace/queued"))
        api.on("GET", "/api/v1/operations/op-config-01", 200, HonkFixtures.body("operations/reload-complete"))
        val path = "config.dae"
        val stack = mutableStateListOf<NavKey>(ConfigSourceKey(path))
        compose.rvContent(dark = false) { RvNavHost(stack) { configEntries(stack) } }
        val editor = hasContentDescription(path) and hasSetTextAction()
        compose.onNode(editor).performTextInput("unique draft text")
        compose.onNodeWithText(path).performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText(RvConfigSourceData.CN).performClick()
        compose.onNode(hasContentDescription(RvConfigSourceData.CN) and hasSetTextAction()).performTextInput("# change\n")
        compose.onNodeWithText("檢視變更").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("套用")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("套用").performClick()
        compose.waitUntil(5_000) { stack.last() == ConfigSourceKey(path) }
        compose.onNodeWithContentDescription("搜尋檔案").performClick()
        compose.onNode(hasContentDescription("搜尋檔案") and hasSetTextAction()).performTextInput("unique draft text")
        compose.onNodeWithText("搜尋檔案").performClick()
        val semantics = compose.onNode(editor).fetchSemanticsNode().config
        val range = semantics[SemanticsProperties.TextSelectionRange]
        assertEquals("unique draft text", semantics[SemanticsProperties.EditableText].text.substring(range.min, range.max))
        val page = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
        page.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
        val bottom = page.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        compose.onNodeWithContentDescription("搜尋檔案").performClick()
        compose.onNode(hasContentDescription("搜尋檔案") and hasSetTextAction()).performTextInput("unique draft text")
        compose.onNodeWithText("搜尋檔案").performClick()
        compose.waitUntil(5_000) { page.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value() < bottom }
    }

    @Test
    @Config(qualifiers = WHOLE_OVERVIEW)
    fun overviewLight() = shot("config-overview", dark = false, whole = true) { Overview() }

    @Test
    @Config(qualifiers = WHOLE_OVERVIEW)
    fun overviewDark() = shot("config-overview", dark = true, whole = true) { Overview() }

    @Test
    @Config(qualifiers = WHOLE_OVERVIEW_EN)
    fun overviewEnLight() = shot("config-overview", dark = false, whole = true, english = true) { Overview() }

    @Test
    @Config(qualifiers = WHOLE_SOURCE)
    fun sourceLight() = shot("config-source", dark = false, whole = true) { Source(edit = CLEAN) }

    @Test
    @Config(qualifiers = WHOLE_SOURCE)
    fun sourceDark() = shot("config-source", dark = true, whole = true) { Source(edit = CLEAN) }

    @Test
    @Config(qualifiers = WHOLE_SOURCE)
    fun errorLight() = shot("config-source-error", dark = false, whole = true) { Source(edit = BROKEN, review = true, broken = true) }

    @Test
    @Config(qualifiers = WHOLE_SOURCE)
    fun errorDark() = shot("config-source-error", dark = true, whole = true) { Source(edit = BROKEN, review = true, broken = true) }

    @Test
    fun diffLight() = shot("config-diff", dark = false, whole = true) { Source(edit = CLEAN, review = true) }

    @Test
    fun diffDark() = shot("config-diff", dark = true, whole = true) { Source(edit = CLEAN, review = true) }

    @Test
    @Config(qualifiers = RV_EN)
    fun diffEnLight() = shot("config-diff", dark = false, english = true) { Source(edit = CLEAN, review = true) }

    @Test
    @Config(qualifiers = WHOLE_STALE)
    fun staleLight() = shot("config-diff-stale", dark = false, whole = true) { Source(edit = CLEAN, review = true, landFirst = true) }

    @Test
    @Config(qualifiers = WHOLE_STALE)
    fun staleDark() = shot("config-diff-stale", dark = true, whole = true) { Source(edit = CLEAN, review = true, landFirst = true) }

    @Test
    @Config(qualifiers = WHOLE_HISTORY)
    fun historyLight() = shot("config-history", dark = false, whole = true) { History() }

    @Test
    @Config(qualifiers = WHOLE_HISTORY)
    fun historyDark() = shot("config-history", dark = true, whole = true) { History() }

    @Test
    fun revisionLight() = shot("config-revision", dark = false) { Revision() }

    @Test
    fun revisionDark() = shot("config-revision", dark = true) { Revision() }

    @Test
    fun importLimitLight() = shot("config-import-limit", dark = false) { Import(PAST_BUDGET) }

    @Test
    fun importLimitDark() = shot("config-import-limit", dark = true) { Import(PAST_BUDGET) }

    @Test
    fun importEscapeLight() = shot("config-import-escape", dark = false) { Import(WITHIN) }

    @Test
    fun importEscapeDark() = shot("config-import-escape", dark = true) { Import(WITHIN) }

    private fun shot(screen: String, dark: Boolean, whole: Boolean = false, english: Boolean = false, content: @Composable () -> Unit) =
        compose.rvShot(screen, dark, before = { if (whole) assertWhole() }, prefix = APP, english = english, content = content)

    @Composable
    private fun Overview() {
        val vm = remember { ConfigOverviewViewModel(PolicyHonkFixtures.configTree(), now = { RV_NOW }, reads = Dispatchers.Unconfined) }
        ConfigOverviewRoute(vm, onBack = {}, routes = ConfigRoutes())
    }

    /**
     * rules/cn.dae with [edit] in the editor, and once [review] honk's check (and the diff when it passed; [broken]
     * honk's verdict on 影音 sent to a group it does not know). [landFirst] has another write of the file land after
     * the editor read it, so the diff is stale.
     */
    @Composable
    private fun Source(edit: String, review: Boolean = false, broken: Boolean = false, landFirst: Boolean = false) {
        val vm = remember {
            val honk = PolicyHonkFixtures.configTree()
            if (broken) honk.on("POST", "/api/v1/config/validate", 200, HonkFixtures.body("config-validate/unknown-target"))
            ConfigSourceViewModel(honk, RvConfigSourceData.CN, reads = Dispatchers.Unconfined).apply {
                if (landFirst) honk.on("GET", "/api/v1/config", 200, HonkFixtures.body("config/rv-config-landed"))
                onValueChange(TextFieldValue(edit))
                if (review) review()
            }
        }
        ConfigSourceRoute(vm, onBack = {}, onApplied = {}, onFile = {})
    }

    @Composable
    private fun History() {
        val vm = remember { ConfigHistoryViewModel(PolicyHonkFixtures.configTree(), now = { RV_NOW }, reads = Dispatchers.Unconfined) }
        ConfigHistoryRoute(vm, onBack = {}, routes = ConfigRoutes())
    }

    @Composable
    private fun Revision() {
        val vm = remember { ConfigRevisionViewModel(PolicyHonkFixtures.configTree(), UNNAMED, now = { RV_NOW }, reads = Dispatchers.Unconfined) }
        ConfigRevisionRoute(vm, onBack = {}, onRestored = {})
    }

    @Composable
    private fun Import(text: String) {
        val vm = remember { ConfigImportViewModel(PolicyHonkFixtures.configTree(), text, PICKED, reads = Dispatchers.Unconfined) }
        ConfigImportRoute(vm, onBack = {}, onPickAgain = {})
    }

    private companion object {
        const val APP = "app-rv"
        const val UNNAMED = 8
        const val PICKED = "router.dae"

        /** A pick past honk's check budget, and one within it (the sample tree's entry). */
        val PAST_BUDGET = "#".repeat(RvConfigLimits.BYTES + 1)
        val WITHIN = RvSampleConfig.texts().getValue(RvSampleConfig.ENTRY)
        val CLEAN = RvSampleConfig.cn("media", wiki = true)
        val BROKEN = RvSampleConfig.cn("stream", wiki = true)

        /** The windows of the approved goldens, as tall as each page whole. */
        const val WHOLE_OVERVIEW = "+h1806dp"
        const val WHOLE_OVERVIEW_EN = "en-w412dp-h1900dp-notnight-xhdpi"
        const val WHOLE_SOURCE = "+h1122dp"
        const val WHOLE_STALE = "+h1030dp"
        const val WHOLE_HISTORY = "+h1019dp"
    }
}
