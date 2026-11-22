package dev.doona.android.testing

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import dev.doona.android.app.ActivityKey
import dev.doona.android.app.DoonaApp
import dev.doona.android.app.GroupKey
import dev.doona.android.app.PoliciesKey
import dev.doona.android.app.SettingsKey
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.Status
import dev.doona.android.engine.fake.FakeEngine
import dev.doona.android.feature.activity.ActivityScreen
import dev.doona.android.feature.activity.sampleActivityState

/** Robolectric draws no system bars, so tests give the app the insets of a phone's status bar and gesture bar. */
val StatusBar = 40.dp
val GestureBar = 24.dp
val FrameInsets = WindowInsets(top = StatusBar, bottom = GestureBar)

/** A screen as the tests render it: a name for files and messages, and its content. */
class ScreenCase(val name: String, val content: @Composable () -> Unit)

/** A connected engine with a minute of traffic: the data every screen test and golden shows. */
fun sampleEngine() = FakeEngine(status = Status.Running(sinceEpochMs = 0), traffic = FakeEngine.SAMPLE_TRAFFIC)

/** Every screen of the app. A screen lane adds its screens here and so gets the spacing check and the goldens. */
fun screenCases(): List<ScreenCase> {
    val engine = sampleEngine()
    return listOf(
        ScreenCase("activity") { DoonaApp(engine, onConnect = {}, listOf(ActivityKey)) },
        ScreenCase("activity-error") {
            ActivityScreen(sampleActivityState(ErrorCode.Busy), onAction = {}, onConnect = {}, onOpenGroup = {})
        },
        ScreenCase("policies") { DoonaApp(engine, onConnect = {}, listOf(ActivityKey, PoliciesKey)) },
        ScreenCase("group") { DoonaApp(engine, onConnect = {}, listOf(ActivityKey, PoliciesKey, GroupKey("g-Proxy"))) },
        ScreenCase("settings") { DoonaApp(engine, onConnect = {}, listOf(ActivityKey, SettingsKey)) },
    )
}
