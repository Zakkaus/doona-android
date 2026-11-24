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
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
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
 * (`proto-rv-config-history-named-*`), the long-press menu where a finger held revision 8 (`-history-menu-*`),
 * revision 8's page, unnamed (`proto-rv-config-revision-*`), revision 7's, named (`-revision-named-*`), and the rename
 * sheet its 名稱 row opens on revision 8 (`-history-rename-*`). Light and dark hold the whole page; fs115 and fs200
 * the first screen.
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
    fun revisionLight() = compose.rvShot("config-revision", dark = false) { Revision(UNNAMED) }

    @Test
    fun revisionDark() = compose.rvShot("config-revision", dark = true) { Revision(UNNAMED) }

    @Test
    fun revisionFs115() = compose.rvShot("config-revision", dark = false, fontScale = RV_FS115) { Revision(UNNAMED) }

    @Test
    fun revisionFs200() = compose.rvShot("config-revision", dark = false, fontScale = RV_FS200) { Revision(UNNAMED) }

    @Test
    fun revisionNamedLight() = compose.rvShot("config-revision-named", dark = false) { Revision(HOME) }

    @Test
    fun revisionNamedDark() = compose.rvShot("config-revision-named", dark = true) { Revision(HOME) }

    @Test
    fun revisionNamedFs115() = compose.rvShot("config-revision-named", dark = false, fontScale = RV_FS115) { Revision(HOME) }

    @Test
    fun revisionNamedFs200() = compose.rvShot("config-revision-named", dark = false, fontScale = RV_FS200) { Revision(HOME) }

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
    fun theNameRowOfARevisionOpensTheSheetOnThatRevision() {
        var asked: RvRevisionRename? = null
        compose.setContent { Themed { RvConfigRevisionPage(revision(HOME), listOf(RvConfigHistoryData.restoreDiff), onBack = {}, onRename = { asked = it }) } }
        compose.onNodeWithText(NAME).performClick()
        assertEquals(RvRevisionRename(HOME, "家用"), asked)
    }

    @Test
    fun anUnnamedRevisionSaysSoAndOffersRestore() {
        var restored = false
        compose.setContent { Themed { RvConfigRevisionPage(revision(UNNAMED), listOf(RvConfigHistoryData.restoreDiff), onBack = {}, onRestore = { restored = true }) } }
        compose.onNodeWithText("未命名").assertExists()
        compose.onNodeWithText("還原").performClick()
        assertEquals(true, restored)
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

    /** The whole path from the list: the row's menu, the sheet's field typed into, 儲存, and the row titled by the name. */
    @Test
    fun aNameTypedInTheSheetTitlesTheRowInTheList() {
        var revisions by mutableStateOf(RvConfigHistoryData.named)
        var rename by mutableStateOf<RvRevisionRename?>(null)
        compose.setContent {
            Themed {
                RvConfigHistoryPage(
                    revisions,
                    RvConfigData.REV,
                    onBack = {},
                    rename = rename,
                    onRename = { rename = it },
                    onSaveName = { rev, name -> revisions = revisions.map { if (it.rev == rev) it.named(name) else it } },
                )
            }
        }
        compose.onNodeWithText("第 8 版").performScrollTo().performTouchInput { longClick() }
        compose.onNodeWithText(RENAME).performClick()
        compose.onNode(hasSetTextAction()).performTextInput(TYPED)
        compose.onNodeWithText(SAVE).performClick()
        compose.onNodeWithText(TYPED).performScrollTo().assertExists()
        compose.onNodeWithText("第 8 版").assertDoesNotExist()
    }

    /** The same from a revision's page: its 名稱 row opens the sheet, and once saved the row shows what was typed. */
    @Test
    fun aNameTypedFromTheNameRowShowsOnTheRevisionsPage() {
        var shown by mutableStateOf(revision(UNNAMED))
        var rename by mutableStateOf<RvRevisionRename?>(null)
        compose.setContent {
            Themed {
                RvConfigRevisionPage(
                    shown,
                    listOf(RvConfigHistoryData.restoreDiff),
                    onBack = {},
                    rename = rename,
                    onRename = { rename = it },
                    onSaveName = { _, name -> shown = shown.named(name) },
                )
            }
        }
        compose.onNodeWithText(NAME).performClick()
        compose.onNode(hasSetTextAction()).performTextInput(TYPED)
        compose.onNodeWithText(SAVE).performClick()
        compose.onNodeWithText(TYPED).assertExists()
        compose.onNodeWithText("未命名").assertDoesNotExist()
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

    /** The named list with the menu where a finger held revision 8's row, as the long-press menu places it. */
    @Composable
    private fun MenuOpen() {
        Box(Modifier.fillMaxSize()) {
            Named()
            RvMenuPanel(rvRevisionMenu {}, onPick = {}, Modifier.offset(menuAt.x, menuAt.y))
        }
    }

    /**
     * Puts the menu's card where [RvLongPressMenu] opens it for a finger a third of the way into revision 8's row, at
     * its vertical centre (as the subscriptions' sample holds its card): its top start at the finger, within the
     * window's margin.
     */
    private fun ComposeContentTestRule.placeMenu() {
        val row = onNodeWithText("第 8 版").fetchSemanticsNode().boundsInRoot
        val window = onRoot().fetchSemanticsNode().boundsInRoot
        menuAt = with(density) {
            val x = (row.left + row.width / 3).toDp()
            DpOffset(x.coerceIn(RvMenuSize.margin, window.right.toDp() - RvMenuSize.margin - RvMenuSize.minWidth), row.center.y.toDp())
        }
        waitForIdle()
    }

    @Composable
    private fun Revision(rev: Int) = RvConfigRevisionPage(revision(rev), listOf(RvConfigHistoryData.restoreDiff), onBack = {})

    private fun revision(rev: Int) = RvConfigHistoryData.named.first { it.rev == rev }

    /** Revision 8's page under the rename sheet its 名稱 row opens (no name yet), as the sheet's window draws it. */
    @Composable
    private fun Rename() {
        Box(Modifier.fillMaxSize()) {
            Revision(UNNAMED)
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
        const val NAME = "名稱"
        const val UNNAMED = 8
        const val HOME = 7
        const val SAVE = "儲存"
        const val TYPED = "回家前"
    }
}
