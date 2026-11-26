package dev.doona.android.feature.config

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.res.stringResource
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RvConfirmContent
import dev.doona.android.designsystem.rv.RvNotice
import dev.doona.android.designsystem.rv.RvNoticeKind
import dev.doona.android.designsystem.rv.RvSheet

/**
 * 匯出設定檔's sheet (config-plan row 16): the credential warning, then 匯出 ([onExport]). honk has one export, every
 * file merged as applied, so the sheet offers no kind (owner, q-export-kind 2026-11-26).
 */
@Composable
internal fun RvConfigExportSheet(visible: Boolean, onExport: () -> Unit, onDismiss: () -> Unit) {
    RvSheet(visible, onDismiss, stringResource(R.string.rv_config_export_title)) { RvConfigExportContent(onExport, onDismiss) }
}

/** What [RvConfigExportSheet] shows in its card. */
@Composable
internal fun ColumnScope.RvConfigExportContent(onExport: () -> Unit, onDismiss: () -> Unit) {
    RvConfirmContent(
        title = stringResource(R.string.rv_config_export_title),
        body = stringResource(R.string.rv_config_export_body),
        confirm = stringResource(R.string.rv_config_export_confirm),
        dismiss = stringResource(R.string.rv_cancel),
        onConfirm = onExport,
        onDismiss = onDismiss,
    )
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
