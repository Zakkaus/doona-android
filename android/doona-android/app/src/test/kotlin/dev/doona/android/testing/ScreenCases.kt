package dev.doona.android.testing

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.doona.android.designsystem.components.LayoutRole
import dev.doona.android.engine.Status
import dev.doona.android.engine.fake.FakeEngine

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

/** A connected engine with a minute of traffic over the groups the goldens were drawn with: the data every screen test and golden shows. */
fun sampleEngine() = FakeEngine(groups = FakeEngine.SAMPLE_GROUPS, status = Status.Running(sinceEpochMs = 0), traffic = FakeEngine.SAMPLE_TRAFFIC)

/**
 * Every old screen of the app. A screen lane adds its screens here and so gets the spacing check and the goldens; it also
 * states the screen's [PageLayout], which the check holds the rendered page to. 設定 was the last: every page is an RV
 * page now, with goldens of its own (plan §2.5).
 */
internal fun screenCases(): List<ScreenCase> = emptyList()
