package dev.doona.android.feature.config

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.res.stringResource
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RvConfirmContent
import dev.doona.android.designsystem.rv.RvNotice
import dev.doona.android.designsystem.rv.RvNoticeKind
import dev.doona.android.designsystem.rv.RvRadioRow
import dev.doona.android.designsystem.rv.RvSettingsRadioCard
import dev.doona.android.designsystem.rv.RvSheet
import dev.doona.android.engine.ExportKind

/**
 * 匯出設定檔's sheet (config-plan row 16): the credential warning, then the two kinds as a radio list, 完整封存 (every
 * file as applied) and 可攜 (a closed tree that loads elsewhere), then 匯出, which stages [kind] ([onExport]). honk has
 * one export, every file merged into one, the portable kind, so 完整封存 stays in its place but disabled until honk
 * exports an archive (owner, 2026-11-27, keep-1127).
 */
@Composable
internal fun RvConfigExportSheet(visible: Boolean, kind: ExportKind, onKind: (ExportKind) -> Unit, onExport: (ExportKind) -> Unit, onDismiss: () -> Unit) {
    RvSheet(visible, onDismiss, stringResource(R.string.rv_config_export_title)) { RvConfigExportContent(kind, onKind, { onExport(kind) }, onDismiss) }
}

// honk-gap:export-archive
internal val HONK_EXPORT_KINDS = setOf(ExportKind.Portable)

/** What [RvConfigExportSheet] shows in its card. */
@Composable
internal fun ColumnScope.RvConfigExportContent(kind: ExportKind, onKind: (ExportKind) -> Unit, onExport: () -> Unit, onDismiss: () -> Unit) {
    RvConfirmContent(
        title = stringResource(R.string.rv_config_export_title),
        body = stringResource(R.string.rv_config_export_body),
        confirm = stringResource(R.string.rv_config_export_confirm),
        dismiss = stringResource(R.string.rv_cancel),
        onConfirm = onExport,
        onDismiss = onDismiss,
    ) {
        RvSettingsRadioCard {
            ExportKind.entries.forEach { each ->
                val (title, line) = when (each) {
                    ExportKind.Archive -> R.string.rv_config_export_archive to R.string.rv_config_export_archive_line
                    ExportKind.Portable -> R.string.rv_config_export_portable to R.string.rv_config_export_portable_line
                }
                RvRadioRow(null, stringResource(title), each == kind, stringResource(line), enabled = each in HONK_EXPORT_KINDS, onSelect = { onKind(each) })
            }
        }
    }
}

/**
 * What the last export left to say, with the [paths] the tree names outside its root, as written: [refused] a portable
 * copy, which staged nothing; otherwise an archive that keeps those declarations but not the files they name.
 */
@Immutable
internal data class RvExportNotice(val refused: Boolean, val paths: List<String>)

/**
 * [notice]'s banner on 版本紀錄: a refused portable copy offers the archive ([onArchive], which opens the sheet on it); an
 * archive that left files out names them and closes ([onSeen]).
 */
@Composable
internal fun rvExportBanner(notice: RvExportNotice?, onArchive: () -> Unit, onSeen: () -> Unit): RvNotice? {
    notice ?: return null
    if (!notice.refused && notice.paths.isEmpty()) {
        return RvNotice(RvNoticeKind.Info, stringResource(R.string.rv_config_export_done), null, stringResource(R.string.rv_done), onSeen)
    }
    val paths = notice.paths.joinToString(stringResource(R.string.rv_effective_list_separator))
    return if (notice.refused) {
        RvNotice(
            RvNoticeKind.Error,
            stringResource(R.string.rv_config_export_refused_title),
            stringResource(R.string.rv_config_export_refused_reason, paths),
            stringResource(R.string.rv_config_export_archive_action),
            onArchive,
            reasonKeepWords = false,
        )
    } else {
        RvNotice(
            RvNoticeKind.Info,
            stringResource(R.string.rv_config_export_partial_title),
            stringResource(R.string.rv_config_export_partial_reason, paths),
            stringResource(R.string.rv_done),
            onSeen,
            reasonKeepWords = false,
        )
    }
}
