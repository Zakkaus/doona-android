package dev.doona.android.prototype.rv.settings

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.tooling.preview.Preview
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.designsystem.rv.rvSystemScheme
import dev.doona.android.feature.NOT_WIRED
import dev.doona.android.feature.model.RvLanguage
import dev.doona.android.feature.report.RvRedaction
import dev.doona.android.feature.report.RvReportBuild
import dev.doona.android.feature.report.RvReportScreen
import dev.doona.android.feature.settings.RvAboutScreen
import dev.doona.android.feature.settings.RvIntervalScreen
import dev.doona.android.feature.settings.RvLanguageScreen
import dev.doona.android.feature.settings.RvLicensesScreen
import dev.doona.android.feature.settings.RvLogLevel
import dev.doona.android.feature.settings.RvLogLevelScreen
import dev.doona.android.prototype.rv.data.RvData

// Previews of the 設定 and 回報問題 pages, whose code is in feature/settings and feature/report, on the sandbox's samples.

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RvSettingsScreenPreview() {
    val dark = isSystemInDarkTheme()
    var state by remember { mutableStateOf(rvSampleSettings()) }
    RvTheme(rvSystemScheme(dark), dark) { RvSettingsScreenSample(state, onChange = { state = it }, onOpenTheme = {}) }
}

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RvReportScreenPreview() {
    val dark = isSystemInDarkTheme()
    var redaction by remember { mutableStateOf(RvRedaction()) }
    RvTheme(rvSystemScheme(dark), dark) { RvReportScreen(RvReportBuild.Ready(redaction), rvSampleReport, redaction, { redaction = it }, onBack = {}, actions = rvReportActions()) }
}

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RvAboutScreenPreview() {
    val dark = isSystemInDarkTheme()
    RvTheme(rvSystemScheme(dark), dark) { RvAboutScreen(rvSampleVersions, onBack = {}, routes = rvAboutRoutes()) }
}

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RvLanguageScreenPreview() {
    val dark = isSystemInDarkTheme()
    var selected by remember { mutableStateOf(RvLanguage.TraditionalChinese) }
    RvTheme(rvSystemScheme(dark), dark) { RvLanguageScreen(selected, RvLanguage.TraditionalChinese, { selected = it }, onBack = {}) }
}

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RvIntervalScreenPreview() {
    val dark = isSystemInDarkTheme()
    var hours by remember { mutableIntStateOf(RvData.UPDATE_HOURS) }
    RvTheme(rvSystemScheme(dark), dark) { RvIntervalScreen(hours, { hours = it }, onBack = {}, onCustom = NOT_WIRED) }
}

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RvLogLevelScreenPreview() {
    val dark = isSystemInDarkTheme()
    var level by remember { mutableStateOf(RvLogLevel.Info) }
    RvTheme(rvSystemScheme(dark), dark) { RvLogLevelScreen(level, { level = it }, onBack = {}) }
}

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RvLicensesScreenPreview() {
    val dark = isSystemInDarkTheme()
    RvTheme(rvSystemScheme(dark), dark) { RvLicensesScreen(onOpen = {}, onBack = {}) }
}
