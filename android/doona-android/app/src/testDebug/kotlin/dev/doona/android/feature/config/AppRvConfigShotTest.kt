package dev.doona.android.feature.config

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.input.TextFieldValue
import dev.doona.android.engine.ConfigDraft
import dev.doona.android.engine.ConfigEditOp
import dev.doona.android.engine.DraftFrom
import dev.doona.android.engine.Engine
import dev.doona.android.engine.SourceAt
import dev.doona.android.engine.fake.RvSampleConfig
import dev.doona.android.engine.fake.rvSampleEngine
import dev.doona.android.prototype.rv.RV_EN
import dev.doona.android.prototype.rv.Utc
import dev.doona.android.prototype.rv.assertWhole
import dev.doona.android.prototype.rv.data.RV_NOW
import dev.doona.android.prototype.rv.rvShot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The configuration pages as the app draws them: the RV sample engine, the page's view model, the mapper, then the
 * page (goldens `app-rv-config-*`). Each is the approved `proto-rv-config-*` golden of the same screen in the same
 * window: the overview is `-overview-skipped-include` (the sample tree's negative rule sits inside an include), the
 * editor and its diffs are rules/cn.dae with 影音 sent to `media` and the wiki rule added, the stale diff lands a second
 * apply first, the revision is 8 and the imports are the fake's two staged picks. The overview differs from its
 * golden only in dns.dae's line count until the owner answers the open question in p14-STATE.
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
    fun errorLight() = shot("config-source-error", dark = false, whole = true) { Source(edit = BROKEN, review = true) }

    @Test
    @Config(qualifiers = WHOLE_SOURCE)
    fun errorDark() = shot("config-source-error", dark = true, whole = true) { Source(edit = BROKEN, review = true) }

    @Test
    fun diffLight() = shot("config-diff", dark = false, whole = true) { Source(edit = CLEAN, review = true, entryEdited = true) }

    @Test
    fun diffDark() = shot("config-diff", dark = true, whole = true) { Source(edit = CLEAN, review = true, entryEdited = true) }

    @Test
    @Config(qualifiers = RV_EN)
    fun diffEnLight() = shot("config-diff", dark = false, english = true) { Source(edit = CLEAN, review = true, entryEdited = true) }

    @Test
    @Config(qualifiers = WHOLE_STALE)
    fun staleLight() = shot("config-diff-stale", dark = false, whole = true) { Source(edit = CLEAN, review = true, entryEdited = true, landFirst = true) }

    @Test
    @Config(qualifiers = WHOLE_STALE)
    fun staleDark() = shot("config-diff-stale", dark = true, whole = true) { Source(edit = CLEAN, review = true, entryEdited = true, landFirst = true) }

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
    fun importLimitLight() = shot("config-import-limit", dark = false) { Import(RvSampleConfig.IMPORT_LIMIT) }

    @Test
    fun importLimitDark() = shot("config-import-limit", dark = true) { Import(RvSampleConfig.IMPORT_LIMIT) }

    @Test
    fun importEscapeLight() = shot("config-import-escape", dark = false) { Import(RvSampleConfig.IMPORT_ESCAPE) }

    @Test
    fun importEscapeDark() = shot("config-import-escape", dark = true) { Import(RvSampleConfig.IMPORT_ESCAPE) }

    private fun shot(screen: String, dark: Boolean, whole: Boolean = false, english: Boolean = false, content: @Composable () -> Unit) =
        compose.rvShot(screen, dark, before = { if (whole) assertWhole() }, prefix = APP, english = english, content = content)

    @Composable
    private fun Overview() {
        val vm = remember { ConfigOverviewViewModel(rvSampleEngine(), now = { RV_NOW }, reads = Dispatchers.Unconfined) }
        ConfigOverviewRoute(vm, onBack = {}, routes = ConfigRoutes())
    }

    /**
     * rules/cn.dae with [edit] in the editor, and once [review] the check (and the diff when it passed). With
     * [entryEdited] the draft already holds the approved diff's other change, 影音's policy in the entry: the editor's
     * file chip, which moves between the files of one draft, is not wired yet, so the engine starts the draft with it.
     * [landFirst] applies another draft after the editor opened, so its base is no longer the head.
     */
    @Composable
    private fun Source(edit: String, review: Boolean = false, entryEdited: Boolean = false, landFirst: Boolean = false) {
        val vm = remember {
            val engine = if (entryEdited) mediaPolicyEdited() else rvSampleEngine()
            ConfigSourceViewModel(engine, RvConfigSourceData.CN, reads = Dispatchers.Unconfined).apply {
                if (landFirst) engine.landAnother()
                onValueChange(TextFieldValue(edit))
                if (review) review()
            }
        }
        ConfigSourceRoute(vm, onBack = {}, onApplied = {})
    }

    @Composable
    private fun History() {
        val vm = remember { ConfigHistoryViewModel(rvSampleEngine(), now = { RV_NOW }, reads = Dispatchers.Unconfined) }
        ConfigHistoryRoute(vm, onBack = {}, routes = ConfigRoutes())
    }

    @Composable
    private fun Revision() {
        val vm = remember { ConfigRevisionViewModel(rvSampleEngine(), UNNAMED, now = { RV_NOW }, reads = Dispatchers.Unconfined) }
        ConfigRevisionRoute(vm, onBack = {}, onRestored = {})
    }

    @Composable
    private fun Import(token: String) {
        val vm = remember { ConfigImportViewModel(rvSampleEngine(), token, PICKED, reads = Dispatchers.Unconfined) }
        ConfigImportRoute(vm, onBack = {}, onPickAgain = {})
    }

    /** The sample engine whose drafts from the head start with 影音's group on `min_moving_avg`. */
    private fun mediaPolicyEdited(): Engine {
        val sample = rvSampleEngine()
        return object : Engine by sample {
            override suspend fun configDraft(from: DraftFrom): Result<ConfigDraft> = sample.configDraft(from).mapCatching { draft ->
                if (from != DraftFrom.Head) return@mapCatching draft
                val entry = sample.configSource(SourceAt.Draft(draft), RvSampleConfig.ENTRY).getOrThrow()
                sample.configEdit(draft, RvSampleConfig.ENTRY, ConfigEditOp.ReplaceText(entry.replace(MEDIA_MIN, MEDIA_MOVING))).getOrThrow()
            }
        }
    }

    /** Applies the head again as a new revision, as another device would. */
    private fun Engine.landAnother() = runBlocking { configApply(configDraft(DraftFrom.Head).getOrThrow()).getOrThrow() }

    private companion object {
        const val APP = "app-rv"
        const val UNNAMED = 8
        const val PICKED = "router.dae"
        const val MEDIA_MIN = "filter: name(keyword: 'HK')\n    policy: min\n"
        const val MEDIA_MOVING = "filter: name(keyword: 'HK')\n    policy: min_moving_avg\n"
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
