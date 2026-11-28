package dev.doona.android.feature.config

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.doona.android.R
import dev.doona.android.designsystem.rv.LocalFrameInsets
import dev.doona.android.designsystem.rv.RV_NO_VALUE
import dev.doona.android.designsystem.rv.RV_ROW_TITLE_LINES
import dev.doona.android.designsystem.rv.Rv
import dev.doona.android.designsystem.rv.RvBannerSlot
import dev.doona.android.designsystem.rv.RvCaption
import dev.doona.android.designsystem.rv.RvCard
import dev.doona.android.designsystem.rv.RvFootnote
import dev.doona.android.designsystem.rv.RvGutter
import dev.doona.android.designsystem.rv.RvLargeTitle
import dev.doona.android.designsystem.rv.RvLongPressMenu
import dev.doona.android.designsystem.rv.RvMenuItem
import dev.doona.android.designsystem.rv.RvNavEnd
import dev.doona.android.designsystem.rv.RvNotice
import dev.doona.android.designsystem.rv.RvNoticeKind
import dev.doona.android.designsystem.rv.RvPageHeader
import dev.doona.android.designsystem.rv.RvPrimaryButton
import dev.doona.android.designsystem.rv.RvScrollPage
import dev.doona.android.designsystem.rv.RvSettingsNavRow
import dev.doona.android.designsystem.rv.RvSettingsRow
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.designsystem.rv.RvStatusChip
import dev.doona.android.designsystem.rv.RvWhole
import dev.doona.android.designsystem.rv.rvConfigLine
import dev.doona.android.designsystem.rv.rvKeepRuns
import dev.doona.android.designsystem.rv.rvKeepTail

/**
 * 版本紀錄 (config plan, history; `configHistory`): the applied revisions newest first, each a plain navigation row
 * with when and how it was applied, the head marked 使用中; a row opens that revision's diff against the head, from
 * which a restore makes a new revision. A revision the user named shows its name, wrapping as long as it is, over its
 * time, origin and number (the number last: a line ending in a two-letter origin would hold 第 N 版 to it, and the
 * whole would not fit the column at a large font size); one without shows its number over its time. A tap opens the
 * revision's page ([RvConfigRevisionPage], where its 名稱 row renames it); a long press offers 重新命名 at the finger as
 * a shortcut, which opens [RvRevisionRenameSheet] on [rename] ([onRename] opens, types into and closes it; [onSaveName]
 * gets the name trimmed, or null to take it away). 匯出設定檔 follows in its own card
 * (`GET /x-honk/config/export`, behind the picker and a confirmation, since the files can carry credentials); [banner] says what the
 * last export left out or refused ([rvExportBanner]).
 */
@Composable
internal fun RvConfigHistoryPage(
    revisions: List<RvRevision>,
    headRev: Int,
    nowEpochMs: Long,
    onBack: () -> Unit,
    onRevision: (Int) -> Unit = {},
    onExport: () -> Unit = {},
    rename: RvRevisionRename? = null,
    onRename: ((RvRevisionRename?) -> Unit)? = null,
    onSaveName: ((Int, String?) -> Unit)? = null,
    banner: RvNotice? = null,
) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val title = stringResource(R.string.rv_config_history)
    RvScrollPage(title, insets, onBack = onBack) { titleBlock ->
        RvLargeTitle(title, titleBlock, insets)
        RvGutter(banner = banner) {
            RvCard {
                revisions.forEach { revision ->
                    val renameRevision = onRename?.let { { it(RvRevisionRename(revision.rev, revision.name.orEmpty())) } }
                    RevisionRow(revision, revision.rev == headRev, nowEpochMs, onRevision, renameRevision)
                }
            }
            RvCard { RvSettingsNavRow(stringResource(R.string.rv_config_export), R.drawable.rv_sym_ios_share, onExport) }
        }
    }
    RvRevisionRenameSheet(rename, onRename, onSaveName)
}

// honk-gap:rev-name

/** The menu a revision's row opens on a long press: 重新命名. */
@Composable
internal fun rvRevisionMenu(onRename: (() -> Unit)?): List<RvMenuItem> =
    listOf(RvMenuItem(stringResource(R.string.rv_sub_rename), R.drawable.rv_sym_edit, enabled = onRename != null, onClick = { onRename?.invoke() }))

@Composable
private fun RevisionRow(revision: RvRevision, head: Boolean, nowEpochMs: Long, onRevision: (Int) -> Unit, onRename: (() -> Unit)?) {
    val number = stringResource(R.string.rv_config_revision, revision.rev)
    // honk-gap:rev-restored-from
    val origin = stringResource(revision.origin.label, revision.restored?.toString() ?: RV_NO_VALUE)
    val inUse: (@Composable () -> Unit)? = if (head) ({ RvStatusChip(R.drawable.rv_nodes_ic_check_circle, stringResource(R.string.rv_template_in_use), Rv.colors.positive) }) else null
    val time = rvConfigWhen(revision.appliedAtEpochMs, nowEpochMs)
    val name = revision.name
    RvLongPressMenu(rvRevisionMenu(onRename), { onRevision(revision.rev) }) { press ->
        RvSettingsRow(
            name ?: number,
            press,
            subtitle = rvConfigLine(listOfNotNull(time, origin, number.takeIf { name != null })),
            titleLines = if (name == null) RV_ROW_TITLE_LINES else Int.MAX_VALUE,
            titleKeepWords = false,
            titleWhole = RvWhole.RevisionName.takeIf { name != null },
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
                        rvKeepRuns(pluralStringResource(R.plurals.rv_config_import_limit, RvConfigLimits.FILES, RvConfigLimits.FILES, RvConfigLimits.SIZE)),
                    ),
                    stringResource(R.string.rv_config_pick_again),
                    onPickAgain,
                ),
                gapBelow = RvSpace.none,
            )
            RvFootnote(stringResource(R.string.rv_config_still_in_use, state.headRev ?: RV_NO_VALUE))
        } else {
            RvBannerSlot(
                RvNotice(
                    RvNoticeKind.Error,
                    rvKeepTail(stringResource(R.string.rv_config_escape_title)),
                    stringResource(R.string.rv_source_line, state.escapeLine, state.escapePath),
                    stringResource(R.string.rv_config_edit_source),
                    onEditSource,
                    reasonKeepWords = false,
                ),
                gapBelow = RvSpace.none,
            )
            RvConfigSection(R.string.rv_config_files) {
                RvCard {
                    state.files.forEach { file ->
                        val origin = file.parent?.let { stringResource(R.string.rv_config_included_by, it) } ?: stringResource(R.string.rv_config_entry)
                        val lines = pluralStringResource(R.plurals.rv_config_lines, file.lines, file.lines)
                        RvSettingsNavRow(file.path, null, { onFile(file.path) }, subtitle = rvConfigLine(listOf(origin, lines)), titleKeepWords = false)
                    }
                }
            }
        }
    }
}
