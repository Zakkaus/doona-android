package dev.doona.android.feature.report

import androidx.compose.runtime.Immutable
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.LogLevel
import dev.doona.android.report.ExportState

/**
 * What the 回報問題 page shows: the core's log [level] (info, the core's own, until it is read), whether an export is
 * on its way to the share sheet, and the last failure: a level the core refused ([levelError]) or an export that could
 * not be written or shared ([exportFailed]).
 */
@Immutable
data class ReportUiState(
    val level: LogLevel = LogLevel.Info,
    val exporting: Boolean = false,
    val exportFailed: Boolean = false,
    val levelError: ErrorCode? = null,
)

sealed interface ReportAction {
    data class SetLevel(val level: LogLevel) : ReportAction

    data object Export : ReportAction

    data object DismissError : ReportAction
}

fun reportUiState(level: LogLevel?, export: ExportState, levelError: ErrorCode?) = ReportUiState(
    level = level ?: LogLevel.Info,
    exporting = export == ExportState.Building || export is ExportState.Ready,
    exportFailed = export == ExportState.Failed,
    levelError = levelError,
)
