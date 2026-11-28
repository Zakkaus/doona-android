package dev.doona.android.testing

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.unit.dp
import dev.doona.android.engine.Status
import dev.doona.android.engine.fake.FakeEngine

/** Robolectric draws no system bars, so tests give the app the insets of a phone's status bar and gesture bar. */
val StatusBar = 40.dp
val GestureBar = 24.dp
val FrameInsets = WindowInsets(top = StatusBar, bottom = GestureBar)

/** A connected engine with a minute of traffic over the groups the goldens were drawn with: the data every screen test and golden shows. */
fun sampleEngine() = FakeEngine(groups = FakeEngine.SAMPLE_GROUPS, status = Status.Running(sinceEpochMs = 0), traffic = FakeEngine.SAMPLE_TRAFFIC)
