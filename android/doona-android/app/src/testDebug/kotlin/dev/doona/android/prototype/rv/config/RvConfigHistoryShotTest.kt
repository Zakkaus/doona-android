package dev.doona.android.prototype.rv.config

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.DpOffset
import dev.doona.android.R
import dev.doona.android.prototype.rv.RV_FS115
import dev.doona.android.prototype.rv.RV_FS200
import dev.doona.android.prototype.rv.RvConfirmContent
import dev.doona.android.prototype.rv.RvMenuPanel
import dev.doona.android.prototype.rv.RvMenuSize
import dev.doona.android.prototype.rv.RvSheetLayout
import dev.doona.android.prototype.rv.RvTheme
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
 * Revisions and import (PV-f item 4), samples only: the nine applied revisions with the head in use
 * (`proto-rv-config-history-*`); an import refused at the 32-file / 8 MiB budget (`proto-rv-config-import-limit-*`);
 * and one staged with an include outside the picked file's directory (`proto-rv-config-import-escape-*`). Named
 * revisions (round 2, item 1): the list with four named, the head's name long enough to wrap
 * (`proto-rv-config-history-named-*`), the long-press menu on revision 8 (`-history-menu-*`) and its rename sheet
 * (`-history-rename-*`). Light and dark hold the whole page; fs115 and fs200 the first screen.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvConfigHistoryShotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    @Test
    @Config(qualifiers = WHOLE_HISTORY)
    fun historyLight() = compose.rvShot("config-history", dark = false, before = { assertWhole() }) { History() }

    @Test
    @Config(qualifiers = WHOLE_HISTORY)
    fun historyDark() = compose.rvShot("config-history", dark = true, before = { assertWhole() }) { History() }

    @Test
    fun historyFs115() = compose.rvShot("config-history", dark = false, fontScale = RV_FS115) { History() }

    @Test
    fun historyFs200() = compose.rvShot("config-history", dark = false, fontScale = RV_FS200) { History() }

    @Test
    @Config(qualifiers = WHOLE_NAMED)
    fun namedLight() = compose.rvShot("config-history-named", dark = false, before = { assertWhole() }) { Named() }

    @Test
    @Config(qualifiers = WHOLE_NAMED)
    fun namedDark() = compose.rvShot("config-history-named", dark = true, before = { assertWhole() }) { Named() }

    @Test
    fun namedFs115() = compose.rvShot("config-history-named", dark = false, fontScale = RV_FS115) { Named() }

    @Test
    fun namedFs200() = compose.rvShot("config-history-named", dark = false, fontScale = RV_FS200) { Named() }

    @Test
    fun menuLight() = compose.rvShot("config-history-menu", dark = false, before = { placeMenu() }) { MenuOpen() }

    @Test
    fun menuDark() = compose.rvShot("config-history-menu", dark = true, before = { placeMenu() }) { MenuOpen() }

    @Test
    fun menuFs115() = compose.rvShot("config-history-menu", dark = false, before = { placeMenu() }, fontScale = RV_FS115) { MenuOpen() }

    @Test
    fun menuFs200() = compose.rvShot("config-history-menu", dark = false, before = { placeMenu() }, fontScale = RV_FS200) { MenuOpen() }

    @Test
    fun renameLight() = compose.rvShot("config-history-rename", dark = false) { Rename() }

    @Test
    fun renameDark() = compose.rvShot("config-history-rename", dark = true) { Rename() }

    @Test
    fun renameFs115() = compose.rvShot("config-history-rename", dark = false, fontScale = RV_FS115) { Rename() }

    @Test
    fun renameFs200() = compose.rvShot("config-history-rename", dark = false, fontScale = RV_FS200) { Rename() }

    @Test
    fun limitLight() = compose.rvShot("config-import-limit", dark = false) { Import(RvConfigHistoryData.limit) }

    @Test
    fun limitDark() = compose.rvShot("config-import-limit", dark = true) { Import(RvConfigHistoryData.limit) }

    @Test
    fun limitFs115() = compose.rvShot("config-import-limit", dark = false, fontScale = RV_FS115) { Import(RvConfigHistoryData.limit) }

    @Test
    fun limitFs200() = compose.rvShot("config-import-limit", dark = false, fontScale = RV_FS200) { Import(RvConfigHistoryData.limit) }

    @Test
    fun escapeLight() = compose.rvShot("config-import-escape", dark = false) { Import(RvConfigHistoryData.escape) }

    @Test
    fun escapeDark() = compose.rvShot("config-import-escape", dark = true) { Import(RvConfigHistoryData.escape) }

    @Test
    fun escapeFs115() = compose.rvShot("config-import-escape", dark = false, fontScale = RV_FS115) { Import(RvConfigHistoryData.escape) }

    @Test
    fun escapeFs200() = compose.rvShot("config-import-escape", dark = false, fontScale = RV_FS200) { Import(RvConfigHistoryData.escape) }

    @Test
    fun aRevisionOpensItsDiff() {
        var opened = 0
        compose.setContent { Themed { RvConfigHistoryPage(RvConfigHistoryData.revisions, RvConfigData.REV, onBack = {}, onRevision = { opened = it }) } }
        compose.onNodeWithText("第 6 版").performScrollTo().performClick()
        assertEquals(6, opened)
    }

    @Test
    fun aNamedRevisionShowsItsNameAndAnUnnamedOneItsNumber() {
        compose.setContent { Themed { RvConfigHistoryPage(RvConfigHistoryData.named, RvConfigData.REV, onBack = {}) } }
        compose.onNodeWithText("家用").assertExists()
        compose.onNodeWithText("第 7 版").assertDoesNotExist()
        compose.onNodeWithText("第 8 版").assertExists()
    }

    @Test
    fun aLongPressOffersRenameWhichOpensTheSheetOnThatRevision() {
        var asked: RvRevisionRename? = null
        compose.setContent { Themed { RvConfigHistoryPage(RvConfigHistoryData.named, RvConfigData.REV, onBack = {}, onRename = { asked = it }) } }
        compose.onNodeWithText("家用").performScrollTo().performTouchInput { longClick() }
        compose.onNodeWithText(RENAME).performClick()
        assertEquals(RvRevisionRename(7, "家用"), asked)
    }

    @Test
    fun savingAnEmptyNameTakesTheNameAway() {
        var saved: Pair<Int, String?>? = null
        var rename by mutableStateOf<RvRevisionRename?>(RvRevisionRename(7, "  "))
        compose.setContent {
            Themed { RvConfigHistoryPage(RvConfigHistoryData.named, RvConfigData.REV, onBack = {}, rename = rename, onRename = { rename = it }, onSaveName = { rev, name -> saved = rev to name }) }
        }
        compose.onNodeWithText(SAVE).performClick()
        assertEquals(7 to null, saved)
        assertEquals(null, rename)
    }

    @Test
    fun savingANameTrimsIt() {
        var saved: Pair<Int, String?>? = null
        compose.setContent {
            Themed { RvConfigHistoryPage(RvConfigHistoryData.named, RvConfigData.REV, onBack = {}, rename = RvRevisionRename(8, " 回家前 "), onSaveName = { rev, name -> saved = rev to name }) }
        }
        compose.onNodeWithText(SAVE).performClick()
        assertEquals(8 to "回家前", saved)
    }

    @Test
    fun anImportWithAnEscapingIncludeCannotGoOn() {
        compose.setContent { Themed { Import(RvConfigHistoryData.escape) } }
        compose.onNodeWithText("檢視變更").assertIsNotEnabled()
    }

    @Composable
    private fun Themed(content: @Composable () -> Unit) = RvTheme(seedColorScheme(Seed.Cyan.argb, false), false, content)

    @Composable
    private fun History() = RvConfigHistoryPage(RvConfigHistoryData.revisions, RvConfigData.REV, onBack = {})

    @Composable
    private fun Import(state: RvImportState) = RvConfigImportPage(state, onBack = {})

    @Composable
    private fun Named() = RvConfigHistoryPage(RvConfigHistoryData.named, RvConfigData.REV, onBack = {})

    /** Where the menu's card goes: set by [placeMenu] once the list is laid out. */
    private var menuAt by mutableStateOf(DpOffset.Zero)

    /** The named list with revision 8's menu open, as the anchored menu places it: its end on the row's, under the row. */
    @Composable
    private fun MenuOpen() {
        Box(Modifier.fillMaxSize()) {
            Named()
            RvMenuPanel(rvRevisionMenu {}, onPick = {}, Modifier.offset(menuAt.x, menuAt.y))
        }
    }

    /** Puts the menu's card [RvMenuSize.anchorGap] under revision 8's row, its end on the row's end ([RvMenu]'s anchored place). */
    private fun ComposeContentTestRule.placeMenu() {
        val row = onNodeWithText("第 8 版").fetchSemanticsNode().boundsInRoot
        menuAt = with(density) { DpOffset(row.right.toDp() - RvMenuSize.minWidth, row.bottom.toDp() + RvMenuSize.anchorGap) }
        waitForIdle()
    }

    /** The named list under the rename sheet for revision 8, which has no name yet, as the sheet's window draws it. */
    @Composable
    private fun Rename() {
        Box(Modifier.fillMaxSize()) {
            Named()
            val title = stringResource(R.string.rv_sub_rename)
            RvSheetLayout({ 1f }, {}, title) {
                RvConfirmContent(title, null, stringResource(R.string.rv_save), stringResource(R.string.rv_cancel), onConfirm = {}, onDismiss = {}) {
                    RvRevisionNameField(RvConfigHistoryData.rename) {}
                }
            }
        }
    }

    private companion object {
        const val WHOLE_HISTORY = "+h1019dp"
        const val WHOLE_NAMED = "+h1045dp"
        const val RENAME = "重新命名"
        const val SAVE = "儲存"
    }
}
