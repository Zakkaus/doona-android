package dev.doona.android.feature.config

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.input.TextFieldValue
import dev.doona.android.engine.fake.RvSampleConfig
import dev.doona.android.prototype.rv.RV_EN
import dev.doona.android.prototype.rv.Utc
import dev.doona.android.prototype.rv.assertWhole
import dev.doona.android.prototype.rv.data.RV_NOW
import dev.doona.android.prototype.rv.rvShot
import dev.doona.android.vpn.HonkFixtures
import dev.doona.android.vpn.PolicyHonkFixtures
import kotlinx.coroutines.Dispatchers
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
        ConfigSourceRoute(vm, onBack = {}, onApplied = {})
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
        const val WHOLE_OVERVIEW = "+h1695dp"
        const val WHOLE_OVERVIEW_EN = "en-w412dp-h1900dp-notnight-xhdpi"
        const val WHOLE_SOURCE = "+h1122dp"
        const val WHOLE_STALE = "+h1030dp"
        const val WHOLE_HISTORY = "+h1019dp"
    }
}
