package dev.doona.android.testing

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.doona.android.app.ActivityKey
import dev.doona.android.app.DoonaApp
import dev.doona.android.app.DoonaAppShell
import dev.doona.android.app.SettingsKey
import dev.doona.android.app.Tab
import dev.doona.android.designsystem.components.CardsGallery
import dev.doona.android.designsystem.components.ControlsGallery
import dev.doona.android.designsystem.components.DialogGallery
import dev.doona.android.designsystem.components.FeedbackGallery
import dev.doona.android.designsystem.components.FieldsGallery
import dev.doona.android.designsystem.components.InputsGallery
import dev.doona.android.designsystem.components.LayoutRole
import dev.doona.android.designsystem.components.StatusGallery
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.Status
import dev.doona.android.engine.fake.FakeEngine
import dev.doona.android.engine.fake.FakeInstalledApps
import dev.doona.android.feature.activity.ActivityScreen
import dev.doona.android.feature.activity.sampleActivityState

/** Robolectric draws no system bars, so tests give the app the insets of a phone's status bar and gesture bar. */
val StatusBar = 40.dp
val GestureBar = 24.dp
val FrameInsets = WindowInsets(top = StatusBar, bottom = GestureBar)

/**
 * What a screen's layout is meant to be, stated in spacing roles so the check does not just read back what was drawn.
 * [shows] are the layout roles the screen has: the probe must find each, or the rules of that role checked nothing.
 * [rhythm] is the gap before each block of the page's sheet after the first.
 */
internal class PageLayout(val shows: Set<LayoutRole>, val rhythm: List<Dp>)

/** A screen as the tests render it: a name for files and messages, its [layout] when the spacing rules apply, and its content. */
internal class ScreenCase(val name: String, val layout: PageLayout?, val content: @Composable () -> Unit)

/** A connected engine with a minute of traffic: the data every screen test and golden shows. */
fun sampleEngine() = FakeEngine(status = Status.Running(sinceEpochMs = 0), traffic = FakeEngine.SAMPLE_TRAFFIC)

/**
 * Every screen of the app. A screen lane adds its screens here and so gets the spacing check and the goldens; it also
 * states the screen's [PageLayout], which the check holds the rendered page to.
 */
internal fun screenCases(): List<ScreenCase> = appScreenCases() + policyScreenCases()

private fun appScreenCases(): List<ScreenCase> {
    val engine = sampleEngine()
    val apps = FakeInstalledApps()
    val space = DoonaTheme.spacing
    val bars = setOf(LayoutRole.Sheet, LayoutRole.TopBar)
    val withHub = bars + LayoutRole.HubBar
    val activity = setOf(LayoutRole.Card, LayoutRole.Row, LayoutRole.Control, LayoutRole.Segmented, LayoutRole.Value, LayoutRole.Chevron)
    return listOf(
        ScreenCase("activity", PageLayout(withHub + activity, listOf(space.afterTitle, space.section, space.section))) {
            DoonaApp(engine, apps, onConnect = {}, listOf(ActivityKey))
        },
        ScreenCase("activity-error", PageLayout(withHub + activity, listOf(space.afterTitle, space.section, space.section))) {
            // The error state sits in the frame of every other screen: the hub bar under it and the gesture bar's inset.
            DoonaAppShell(Tab.Activity, onSelect = {}) { ActivityScreen(sampleActivityState(ErrorCode.Busy), onAction = {}, onConnect = {}, onOpenGroup = {}) }
        },
        ScreenCase(
            "settings",
            PageLayout(withHub + setOf(LayoutRole.ListCard, LayoutRole.ListRow, LayoutRole.Mark), listOf(space.afterTitle)),
        ) { DoonaApp(engine, apps, onConnect = {}, listOf(ActivityKey, SettingsKey)) },
    ) + perAppCases()
}

/**
 * The component gallery pages. They get goldens and the accessibility check like a screen, but not the spacing rules,
 * since a gallery lists components rather than laying out a page.
 */
internal fun galleryCases(): List<ScreenCase> = listOf(
    ScreenCase("gallery-controls", null) { ControlsGallery() },
    ScreenCase("gallery-cards", null) { CardsGallery() },
    ScreenCase("gallery-status", null) { StatusGallery() },
    ScreenCase("gallery-inputs", null) { InputsGallery() },
    ScreenCase("gallery-fields", null) { FieldsGallery() },
    ScreenCase("gallery-feedback", null) { FeedbackGallery() },
    ScreenCase("gallery-dialog", null) { DialogGallery() },
)
