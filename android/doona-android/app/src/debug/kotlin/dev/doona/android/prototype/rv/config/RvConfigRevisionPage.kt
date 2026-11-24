package dev.doona.android.prototype.rv.config

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import dev.doona.android.R
import dev.doona.android.designsystem.theme.LocalFrameInsets
import dev.doona.android.prototype.rv.RvCaption
import dev.doona.android.prototype.rv.RvCard
import dev.doona.android.prototype.rv.RvChevron
import dev.doona.android.prototype.rv.RvConfirmSheet
import dev.doona.android.prototype.rv.RvDiffAt
import dev.doona.android.prototype.rv.RvDiffFile
import dev.doona.android.prototype.rv.RvFootnote
import dev.doona.android.prototype.rv.RvGutter
import dev.doona.android.prototype.rv.RvIconDisc
import dev.doona.android.prototype.rv.RvPageHeader
import dev.doona.android.prototype.rv.RvPrimaryButton
import dev.doona.android.prototype.rv.RvRow
import dev.doona.android.prototype.rv.RvScrollPage
import dev.doona.android.prototype.rv.RvSourceDiff
import dev.doona.android.prototype.rv.RvTextField
import dev.doona.android.prototype.rv.RvTheme
import dev.doona.android.prototype.rv.rvSystemScheme

/**
 * One revision, opened from its 版本紀錄 row (config plan, history; `configDraft from: revision`): its number as the
 * large title over when it was applied and how, a caption line each, then its 名稱 row (the name, or 未命名), which opens the rename sheet
 * as 訂閱's name row does, so naming needs no long press. Under 檔案, what restoring it changes against the head, one
 * [RvSourceDiff] per file, a tap opening the file at that line; 還原 applies it as a new revision. The head itself has
 * nothing to restore: no files, no 還原.
 */
@Composable
internal fun RvConfigRevisionPage(
    revision: RvRevision,
    files: List<RvDiffFile>,
    onBack: () -> Unit,
    onRestore: () -> Unit = {},
    onOpen: (String, RvDiffAt) -> Unit = { _, _ -> },
    rename: RvRevisionRename? = null,
    onRename: (RvRevisionRename?) -> Unit = {},
    onSaveName: (Int, String?) -> Unit = { _, _ -> },
) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val title = stringResource(R.string.rv_config_revision, revision.rev)
    val origin = revision.restored?.let { stringResource(revision.origin.label, it) } ?: stringResource(revision.origin.label)
    val restore: (@Composable () -> Unit)? = if (files.isEmpty()) null else ({ RvPrimaryButton(stringResource(R.string.rv_config_restore), onRestore) })
    RvScrollPage(title, insets, onBack = onBack, bottomAction = restore, actionScrim = true) { titleBlock ->
        // Two caption lines, as a group's page has: one line cut 還原自第 6 版 short at a large font size.
        RvPageHeader(
            title,
            titleBlock,
            insets,
            caption = {
                RvCaption(rvConfigWhen(revision.appliedAtEpochMs))
                RvCaption(origin)
            },
        )
        RvGutter {
            RvCard {
                RvRow(
                    { RvIconDisc(R.drawable.rv_sym_edit) },
                    stringResource(R.string.rv_sub_name),
                    revision.name ?: stringResource(R.string.rv_config_unnamed),
                    onClick = { onRename(RvRevisionRename(revision.rev, revision.name.orEmpty())) },
                ) { RvChevron() }
            }
        }
        if (files.isNotEmpty()) {
            RvConfigSection(R.string.rv_config_files) { files.forEach { file -> RvSourceDiff(file, onOpen = { onOpen(file.source, it) }) } }
            RvFootnote(stringResource(R.string.rv_template_new_flows))
        }
    }
    RvRevisionRenameSheet(rename, onRename, onSaveName)
}

/**
 * The rename sheet over 版本紀錄 and a revision's page: open while [rename] is set ([onRename] types into and closes
 * it); 儲存 gives [onSaveName] the name trimmed, or null for an empty field, which takes the name away.
 */
@Composable
internal fun RvRevisionRenameSheet(rename: RvRevisionRename?, onRename: (RvRevisionRename?) -> Unit, onSaveName: (Int, String?) -> Unit) {
    RvConfirmSheet(
        visible = rename != null,
        title = stringResource(R.string.rv_sub_rename),
        body = null,
        confirm = stringResource(R.string.rv_save),
        dismiss = stringResource(R.string.rv_cancel),
        onConfirm = {
            rename?.let { onSaveName(it.rev, it.name.trim().ifEmpty { null }) }
            onRename(null)
        },
        onDismiss = { onRename(null) },
    ) { rename?.let { RvRevisionNameField(it) { name -> onRename(it.copy(name = name)) } } }
}

/**
 * The rename sheet's field: the sheet's title names it, so it has no label of its own; empty, it shows the revision's
 * number, which is what the list row says while the revision has no name (the placeholder is that value, not a hint).
 */
@Composable
internal fun RvRevisionNameField(rename: RvRevisionRename, onName: (String) -> Unit) {
    RvTextField(rename.name, onName, label = null, name = stringResource(R.string.rv_sub_rename), placeholder = stringResource(R.string.rv_config_revision, rename.rev))
}

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RvConfigRevisionPagePreview() {
    val dark = isSystemInDarkTheme()
    RvTheme(rvSystemScheme(dark), dark) { RvConfigRevisionPage(RvConfigHistoryData.named[2], listOf(RvConfigHistoryData.restoreDiff), onBack = {}) }
}
