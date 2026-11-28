package dev.doona.android.feature.config

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RvListEdit
import dev.doona.android.designsystem.rv.RvNotice
import dev.doona.android.designsystem.rv.RvNoticeKind
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.prototype.rv.RV_FS200
import dev.doona.android.prototype.rv.Seed
import dev.doona.android.prototype.rv.Utc
import dev.doona.android.prototype.rv.assertWhole
import dev.doona.android.prototype.rv.rvShot
import dev.doona.android.prototype.rv.seedColorScheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 設定檔's files with the add and remove controls (owner 2026-11-26 「要加要減的按鈕我都要看到」; on the right, 13:53):
 * `config-files-edit` a remove control at each include's end, the entry's place empty, 新增檔案 ending the card; `-confirm` the
 * question for nodes.dae, whose include nodes/office.dae goes with it; `-add` the new file's sheet; `-failed` a removal
 * the phone could not stage; `-empty` the entry alone after the last include went.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvConfigFilesEditShotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    @Test
    @Config(qualifiers = WHOLE_EDIT)
    fun editLight() = shot("config-files-edit", false) { Page() }

    @Test
    @Config(qualifiers = WHOLE_EDIT)
    fun editDark() = shot("config-files-edit", true) { Page() }

    @Test
    @Config(qualifiers = WHOLE_EDIT_FS200)
    fun editFs200() = compose.rvShot("config-files-edit", false, before = { assertWhole() }, fontScale = RV_FS200) { Page() }

    @Test
    @Config(qualifiers = "en-w412dp-h1875dp-notnight-xhdpi")
    fun editEnLight() = compose.rvShot("config-files-edit", false, before = { assertWhole() }, english = true) { Page() }

    @Test fun confirmLight() = confirm(false)

    @Test fun confirmDark() = confirm(true)

    @Test fun addLight() = compose.rvShot("config-files-edit-add", false, windows = true) { Page(addFile = RvConfigAddFile(NEW_FILE, {}, {})) }

    @Test fun addDark() = compose.rvShot("config-files-edit-add", true, windows = true) { Page(addFile = RvConfigAddFile(NEW_FILE, {}, {})) }

    @Test
    @Config(qualifiers = WHOLE_FAILED)
    fun failedLight() = shot("config-files-edit-failed", false) { Page(banner = failed()) }

    @Test
    @Config(qualifiers = WHOLE_FAILED)
    fun failedDark() = shot("config-files-edit-failed", true) { Page(banner = failed()) }

    @Test
    @Config(qualifiers = WHOLE_EMPTY)
    fun emptyLight() = shot("config-files-edit-empty", false) { Page(state = ENTRY_ONLY) }

    @Test
    @Config(qualifiers = WHOLE_EMPTY)
    fun emptyDark() = shot("config-files-edit-empty", true) { Page(state = ENTRY_ONLY) }

    @Test
    fun anIncludesRemoveControlAsksAndTheSheetRemovesTheFileAndItsIncludes() {
        val removed = mutableListOf<String>()
        compose.setContent { Themed { RvConfigOverviewPage(RvConfigData.overview, onBack = {}, edit = RvListEdit({}, { removed += it })) } }
        compose.onNodeWithContentDescription("刪除 ${RvConfigData.ENTRY}").assertDoesNotExist()
        compose.onNodeWithContentDescription("刪除 ${RvConfigData.NODES}").performScrollTo().performClick()
        compose.onNodeWithText("${RvConfigData.ENTRY} 中引入它的那一行會一起刪除，它引入的 nodes/office.dae 也會刪除。套用前會先顯示變更。").assertExists()
        compose.onNodeWithText("刪除").performClick()
        assertEquals(listOf(RvConfigData.NODES), removed)
    }

    /** 新增 takes a relative `.dae` path that is not in the tree yet. */
    @Test
    fun theNewFileSheetTakesANewRelativeDaePath() {
        var path by mutableStateOf(RvConfigData.NODES)
        val created = mutableListOf<String>()
        val add = { RvConfigAddFile(path, { it?.let { p -> path = p } }, { created += it }) }
        compose.setContent { Themed { RvConfigOverviewPage(RvConfigData.overview, onBack = {}, edit = RvListEdit({}, {}), addFile = add()) } }
        listOf(RvConfigData.NODES, "../outside.dae", "/etc/honk.dae", "notes.txt").forEach {
            path = it
            compose.onNodeWithText("新增").assertIsNotEnabled()
        }
        path = NEW_FILE
        compose.onNodeWithText("新增").assertIsEnabled().performClick()
        assertEquals(listOf(NEW_FILE), created)
    }

    private fun confirm(dark: Boolean) = compose.rvShot(
        "config-files-edit-confirm",
        dark,
        before = { onNodeWithContentDescription("刪除 ${RvConfigData.NODES}").performScrollTo().performClick() },
        windows = true,
    ) { Page() }

    private fun shot(name: String, dark: Boolean, content: @Composable () -> Unit) = compose.rvShot(name, dark, before = { assertWhole() }, content = content)

    @Composable
    private fun Page(state: RvConfigState = RvConfigData.overview, addFile: RvConfigAddFile? = null, banner: RvNotice? = null) =
        RvConfigOverviewPage(state, onBack = {}, edit = RvListEdit({}, {}), addFile = addFile, banner = banner)

    @Composable
    private fun failed() = RvNotice(
        RvNoticeKind.Error,
        stringResource(R.string.rv_list_delete_failed_title, "rules/cn.dae"),
        stringResource(R.string.rv_list_delete_failed_storage),
        stringResource(R.string.rv_retry),
        {},
        titleKeepWords = false,
    )

    @Composable
    private fun Themed(content: @Composable () -> Unit) = RvTheme(seedColorScheme(Seed.Cyan.argb, false), false, content)

    private companion object {
        const val NEW_FILE = "rules/work.dae"

        /**
         * After the last include went: config.dae alone, 29 lines without its include block, declaring its four groups
         * and nothing else (RvSampleConfig's entry).
         */
        val ENTRY_ONLY = RvConfigData.overview.copy(
            files = listOf(RvConfigFile(RvConfigData.ENTRY, null, 29)),
            summary = RvConfigSummary(subscriptions = 0, nodes = 0, groups = 4, trafficRules = 0, dnsUpstreams = 0, dnsRequestRules = 0, dnsResponseRules = 0),
        )

        /** Windows as tall as each whole page. */
        const val WHOLE_EDIT = "+h1874dp"
        const val WHOLE_EDIT_FS200 = "+h2403dp"
        const val WHOLE_FAILED = "+h2031dp"
        const val WHOLE_EMPTY = "+h1464dp"
    }
}
