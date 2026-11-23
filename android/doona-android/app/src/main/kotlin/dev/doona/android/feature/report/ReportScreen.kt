package dev.doona.android.feature.report

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.doona.android.R
import dev.doona.android.designsystem.components.DoonaButton
import dev.doona.android.designsystem.components.DoonaErrorBanner
import dev.doona.android.designsystem.components.DoonaGap
import dev.doona.android.designsystem.components.DoonaPage
import dev.doona.android.designsystem.components.DoonaPageTitle
import dev.doona.android.designsystem.components.DoonaSectionTitle
import dev.doona.android.designsystem.components.DoonaSegmented
import dev.doona.android.designsystem.components.DoonaText
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.engine.Engine
import dev.doona.android.engine.LogLevel
import dev.doona.android.feature.messageRes
import dev.doona.android.report.Reports

/** The 回報問題 entry: the export it starts outlives the page, in [reports]; the activity opens the share sheet. */
@Composable
fun ReportRoute(engine: Engine, reports: Reports, onBack: () -> Unit) {
    val viewModel: ReportViewModel = viewModel(factory = ReportViewModel.factory(engine, reports))
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ReportScreen(state, viewModel::onAction, onBack)
}

/**
 * 回報問題: the core's log level, then the diagnostic report, exported to the app the user picks in the share sheet;
 * nothing is uploaded. Laid out as 依 App is: the control and its note, then the one action and its note.
 */
@Composable
fun ReportScreen(state: ReportUiState, onAction: (ReportAction) -> Unit, onBack: () -> Unit) {
    val space = DoonaTheme.spacing
    val c = DoonaTheme.colors
    DoonaPage(onBack = onBack) {
        DoonaPageTitle(stringResource(R.string.report_title))
        Failures(state, onAction)
        DoonaSectionTitle(stringResource(R.string.report_log_level))
        DoonaSegmented(
            LogLevel.entries.map { stringResource(it.labelRes()) },
            selected = state.level.ordinal,
            onSelect = { onAction(ReportAction.SetLevel(LogLevel.entries[it])) },
        )
        DoonaGap(space.block)
        DoonaText(stringResource(R.string.report_level_note), style = DoonaTheme.type.footnote, color = c.textSecondary)
        DoonaSectionTitle(stringResource(R.string.report_section))
        DoonaButton(stringResource(R.string.report_export), { onAction(ReportAction.Export) }, busy = state.exporting)
        DoonaGap(space.item)
        DoonaText(stringResource(R.string.report_note), style = DoonaTheme.type.footnote, color = c.textSecondary)
    }
}

/** A level the core refused says so by its code; an export that failed says that it did. */
@Composable
private fun Failures(state: ReportUiState, onAction: (ReportAction) -> Unit) {
    val messages = listOfNotNull(
        state.levelError?.let { stringResource(it.messageRes()) },
        stringResource(R.string.report_export_failed).takeIf { state.exportFailed },
    )
    for (message in messages) {
        DoonaGap(DoonaTheme.spacing.afterTitle)
        DoonaErrorBanner(message, stringResource(R.string.ui_dismiss), { onAction(ReportAction.DismissError) })
    }
}

@StringRes
internal fun LogLevel.labelRes(): Int = when (this) {
    LogLevel.Error -> R.string.report_level_error
    LogLevel.Warn -> R.string.report_level_warn
    LogLevel.Info -> R.string.report_level_info
    LogLevel.Debug -> R.string.report_level_debug
}
