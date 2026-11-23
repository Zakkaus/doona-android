package dev.doona.android.feature.report

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.engine.LogLevel
import dev.doona.android.report.ExportState

/** The page as it opens, on the core's own level. */
fun sampleReportState() = reportUiState(LogLevel.Info, ExportState.Idle, null)

/** An export on its way to the share sheet, after the level was set to 除錯. */
fun sampleExportingState() = reportUiState(LogLevel.Debug, ExportState.Building, null)

/** An export that could not be written. */
fun sampleExportFailedState() = reportUiState(LogLevel.Info, ExportState.Failed, null)

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ReportScreenPreview() {
    DoonaTheme { ReportScreen(sampleReportState(), onAction = {}, onBack = {}) }
}

@Preview(name = "Exporting", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Composable
private fun ReportExportingPreview() {
    DoonaTheme { ReportScreen(sampleExportingState(), onAction = {}, onBack = {}) }
}

@Preview(name = "Failed", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Composable
private fun ReportFailedPreview() {
    DoonaTheme { ReportScreen(sampleExportFailedState(), onAction = {}, onBack = {}) }
}
