package dev.doona.android.testing

import androidx.compose.runtime.Composable
import dev.doona.android.app.ActivityKey
import dev.doona.android.app.DoonaApp
import dev.doona.android.app.DoonaAppShell
import dev.doona.android.app.ReportKey
import dev.doona.android.app.SettingsKey
import dev.doona.android.app.Tab
import dev.doona.android.designsystem.components.LayoutRole
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.engine.fake.FakeInstalledApps
import dev.doona.android.feature.report.ReportScreen
import dev.doona.android.feature.report.ReportUiState
import dev.doona.android.feature.report.sampleExportFailedState
import dev.doona.android.feature.report.sampleExportingState

/**
 * The 回報問題 page: the real route on the fake engine, which reads the core's level as 一般, then an export under way
 * and one that failed, in the frame of the app.
 */
internal fun reportCases(): List<ScreenCase> {
    val roles = setOf(LayoutRole.Sheet, LayoutRole.TopBar, LayoutRole.HubBar, LayoutRole.Heading, LayoutRole.Segmented, LayoutRole.Button)
    val space = DoonaTheme.spacing
    // A section title holds the space above and below it (sectionAbove, sectionBelow), so nothing lies between it and
    // its neighbours: the title, 日誌等級, the control, its note a block under it, 診斷報告, the button, its note an item under it.
    val rhythm = listOf(space.none, space.none, space.block, space.none, space.none, space.item)

    fun shown(state: ReportUiState): @Composable () -> Unit = {
        DoonaAppShell(Tab.Settings, onSelect = {}) { ReportScreen(state, onAction = {}, onBack = {}) }
    }
    return listOf(
        ScreenCase("report", PageLayout(roles, rhythm)) {
            DoonaApp(sampleEngine(), FakeInstalledApps(), onConnect = {}, listOf(ActivityKey, SettingsKey, ReportKey))
        },
        ScreenCase("report-exporting", PageLayout(roles, rhythm), shown(sampleExportingState())),
        // The banner sits under the title as on every page, and the first section title follows it directly.
        ScreenCase("report-failed", PageLayout(roles + LayoutRole.Card, listOf(space.afterTitle) + rhythm), shown(sampleExportFailedState())),
    )
}

/** The screens of the diagnostic report the owner reviews before a release: they get [ReviewVariants] too. */
internal val reviewedScreens = setOf("settings", "activity-crash", "report", "report-exporting", "report-failed")
