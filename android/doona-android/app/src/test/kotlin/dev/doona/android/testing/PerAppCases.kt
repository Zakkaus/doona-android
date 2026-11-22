package dev.doona.android.testing

import androidx.compose.runtime.Composable
import dev.doona.android.app.ActivityKey
import dev.doona.android.app.DoonaApp
import dev.doona.android.app.DoonaAppShell
import dev.doona.android.app.PerAppKey
import dev.doona.android.app.SettingsKey
import dev.doona.android.app.Tab
import dev.doona.android.designsystem.components.LayoutRole
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.engine.fake.FakeInstalledApps
import dev.doona.android.feature.perapp.PerAppScreen
import dev.doona.android.feature.perapp.PerAppUiState
import dev.doona.android.feature.perapp.sampleBusyState
import dev.doona.android.feature.perapp.sampleCheckedState
import dev.doona.android.feature.perapp.sampleErrorState
import dev.doona.android.feature.perapp.sampleNeedsAppState
import dev.doona.android.feature.perapp.sampleSearchState

/**
 * The 依 App page in each state it has. The first is the real route on the fake engine and the fake apps, which is the
 * saved policy 全部 App; the others show [PerAppScreen] on a state built by the same pure function the ViewModel uses,
 * in the frame of the app.
 */
internal fun perAppCases(): List<ScreenCase> {
    val space = DoonaTheme.spacing
    val bars = setOf(LayoutRole.Sheet, LayoutRole.TopBar, LayoutRole.HubBar)
    val controls = bars + setOf(LayoutRole.Control, LayoutRole.Button)
    val searched = controls + LayoutRole.Field
    val listed = searched + LayoutRole.Row

    // A page on a settled state sits in the frame of the other pages: the hub bar under it and the gesture bar's inset.
    fun shown(state: PerAppUiState): @Composable () -> Unit = {
        DoonaAppShell(Tab.Settings, onSelect = {}) { PerAppScreen(state, onAction = {}, onBack = {}) }
    }
    return listOf(
        ScreenCase("perapp", PageLayout(controls, emptyList())) {
            DoonaApp(sampleEngine(), FakeInstalledApps(), onConnect = {}, listOf(ActivityKey, SettingsKey, PerAppKey))
        },
        ScreenCase("perapp-checked", PageLayout(listed, emptyList()), shown(sampleCheckedState())),
        ScreenCase("perapp-busy", PageLayout(listed, emptyList()), shown(sampleBusyState())),
        ScreenCase("perapp-search", PageLayout(listed, emptyList()), shown(sampleSearchState())),
        ScreenCase("perapp-no-match", PageLayout(searched, emptyList()), shown(sampleSearchState("zzz"))),
        // The note and the banner make the header taller than a landscape window, so no row is composed at the first offset.
        ScreenCase("perapp-needs-app", PageLayout(searched, emptyList()), shown(sampleNeedsAppState())),
        ScreenCase("perapp-error", PageLayout(searched, emptyList()), shown(sampleErrorState())),
    )
}
