package dev.doona.android.prototype.rv.config

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import dev.doona.android.R
import dev.doona.android.designsystem.theme.LocalFrameInsets
import dev.doona.android.prototype.rv.RV_ROW_TITLE_LINES
import dev.doona.android.prototype.rv.Rv
import dev.doona.android.prototype.rv.RvBannerSlot
import dev.doona.android.prototype.rv.RvCaption
import dev.doona.android.prototype.rv.RvCard
import dev.doona.android.prototype.rv.RvFootnote
import dev.doona.android.prototype.rv.RvGutter
import dev.doona.android.prototype.rv.RvLargeTitle
import dev.doona.android.prototype.rv.RvLongPressMenu
import dev.doona.android.prototype.rv.RvMenuItem
import dev.doona.android.prototype.rv.RvNotice
import dev.doona.android.prototype.rv.RvNoticeKind
import dev.doona.android.prototype.rv.RvPageHeader
import dev.doona.android.prototype.rv.RvPrimaryButton
import dev.doona.android.prototype.rv.RvScrollPage
import dev.doona.android.prototype.rv.RvSpace
import dev.doona.android.prototype.rv.RvTheme
import dev.doona.android.prototype.rv.nodes.RvStatusChip
import dev.doona.android.prototype.rv.rvKeepRuns
import dev.doona.android.prototype.rv.rvKeepTail
import dev.doona.android.prototype.rv.rvSystemScheme
import dev.doona.android.prototype.rv.settings.RvNavEnd
import dev.doona.android.prototype.rv.settings.RvSettingsNavRow
import dev.doona.android.prototype.rv.settings.RvSettingsRow

/**
 * 版本紀錄 (config plan, history; `configHistory`): the applied revisions newest first, each a plain navigation row
 * with when and how it was applied, the head marked 使用中; a row opens that revision's diff against the head, from
 * which a restore makes a new revision. A revision the user named shows its name, wrapping as long as it is, over its
 * time, origin and number (the number last: a line ending in a two-letter origin would hold 第 N 版 to it, and the
 * whole would not fit the column at a large font size); one without shows its number over its time. A tap opens the
 * revision's page ([RvConfigRevisionPage], where its 名稱 row renames it); a long press offers 重新命名 at the finger as
 * a shortcut, which opens [RvRevisionRenameSheet] on [rename] ([onRename] opens, types into and closes it; [onSaveName]
 * gets the name trimmed, or null to take it away). 匯出設定檔 follows in its own card
 * (`configExport`, behind the picker and a confirmation, since the files can carry credentials).
 */
@Composable
internal fun RvConfigHistoryPage(
    revisions: List<RvRevision>,
    headRev: Int,
    onBack: () -> Unit,
    onRevision: (Int) -> Unit = {},
    onExport: () -> Unit = {},
    rename: RvRevisionRename? = null,
    onRename: (RvRevisionRename?) -> Unit = {},
    onSaveName: (Int, String?) -> Unit = { _, _ -> },
) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val title = stringResource(R.string.rv_config_history)
    RvScrollPage(title, insets, onBack = onBack) { titleBlock ->
        RvLargeTitle(title, titleBlock, insets)
        RvGutter {
            RvCard { revisions.forEach { RevisionRow(it, it.rev == headRev, onRevision) { onRename(RvRevisionRename(it.rev, it.name.orEmpty())) } } }
            RvCard { RvSettingsNavRow(stringResource(R.string.rv_config_export), R.drawable.rv_sym_ios_share, onExport) }
        }
        Spacer(Modifier.height(RvSpace.gutter))
        Spacer(Modifier.windowInsetsBottomHeight(insets))
    }
    RvRevisionRenameSheet(rename, onRename, onSaveName)
}

/** The menu a revision's row opens on a long press: 重新命名. */
@Composable
internal fun rvRevisionMenu(onRename: () -> Unit): List<RvMenuItem> = listOf(RvMenuItem(stringResource(R.string.rv_sub_rename), R.drawable.rv_sym_edit, onClick = onRename))

@Composable
private fun RevisionRow(revision: RvRevision, head: Boolean, onRevision: (Int) -> Unit, onRename: () -> Unit) {
    val number = stringResource(R.string.rv_config_revision, revision.rev)
    val origin = revision.restored?.let { stringResource(revision.origin.label, it) } ?: stringResource(revision.origin.label)
    val inUse: (@Composable () -> Unit)? = if (head) ({ RvStatusChip(R.drawable.rv_nodes_ic_check_circle, stringResource(R.string.rv_template_in_use), Rv.colors.positive) }) else null
    val time = rvConfigWhen(revision.appliedAtEpochMs)
    val name = revision.name
    RvLongPressMenu(rvRevisionMenu(onRename), { onRevision(revision.rev) }) { press ->
        RvSettingsRow(
            name ?: number,
            press,
            subtitle = rvConfigLine(if (name == null) listOf(time, origin) else listOf(time, origin, number)),
            titleLines = if (name == null) RV_ROW_TITLE_LINES else Int.MAX_VALUE,
        ) { RvNavEnd(inUse) }
    }
}

/**
 * 匯入設定檔 after the picker (config plan, import boundary): the picked file under the large title. Refused at the
 * budget, a banner says so with the limit and offers another file, and a footnote says the head is still in use.
 * Staged with an include outside the picked file's directory, the draft's files follow a banner naming the line and the
 * path, which offers the source; 檢視變更 waits until the include is fixed.
 */
@Composable
internal fun RvConfigImportPage(
    state: RvImportState,
    onBack: () -> Unit,
    onPickAgain: () -> Unit = {},
    onEditSource: () -> Unit = {},
    onFile: (String) -> Unit = {},
) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val title = stringResource(R.string.rv_config_import)
    val review: (@Composable () -> Unit)? = if (state.refusedAtLimit) null else ({ RvPrimaryButton(stringResource(R.string.rv_template_review), {}, enabled = false) })
    RvScrollPage(title, insets, onBack = onBack, bottomAction = review) { titleBlock ->
        RvPageHeader(title, titleBlock, insets, caption = { RvCaption(state.name) })
        if (state.refusedAtLimit) {
            RvBannerSlot(
                RvNotice(
                    RvNoticeKind.Error,
                    stringResource(R.string.rv_config_import_refused),
                    rvKeepTail(
                        rvKeepRuns(pluralStringResource(R.plurals.rv_config_import_limit, RvConfigHistoryData.LIMIT_FILES, RvConfigHistoryData.LIMIT_FILES, RvConfigHistoryData.LIMIT_SIZE)),
                    ),
                    stringResource(R.string.rv_config_pick_again),
                    onPickAgain,
                ),
            )
            RvFootnote(stringResource(R.string.rv_config_still_in_use, state.headRev))
        } else {
            RvBannerSlot(
                RvNotice(
                    RvNoticeKind.Error,
                    rvKeepTail(stringResource(R.string.rv_config_escape_title)),
                    stringResource(R.string.rv_source_line, state.escapeLine, state.escapePath),
                    stringResource(R.string.rv_config_edit_source),
                    onEditSource,
                ),
            )
            RvConfigSection(R.string.rv_config_files) {
                RvCard {
                    state.files.forEach { file ->
                        val origin = file.parent?.let { stringResource(R.string.rv_config_included_by, it) } ?: stringResource(R.string.rv_config_entry)
                        val lines = pluralStringResource(R.plurals.rv_config_lines, file.lines, file.lines)
                        RvSettingsNavRow(file.path, null, { onFile(file.path) }, subtitle = rvConfigLine(listOf(origin, lines)))
                    }
                }
            }
        }
    }
}

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RvConfigHistoryPagePreview() {
    val dark = isSystemInDarkTheme()
    RvTheme(rvSystemScheme(dark), dark) { RvConfigHistoryPage(RvConfigHistoryData.revisions, RvConfigData.REV, onBack = {}) }
}

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RvConfigImportPagePreview() {
    val dark = isSystemInDarkTheme()
    RvTheme(rvSystemScheme(dark), dark) { RvConfigImportPage(RvConfigHistoryData.escape, onBack = {}) }
}
