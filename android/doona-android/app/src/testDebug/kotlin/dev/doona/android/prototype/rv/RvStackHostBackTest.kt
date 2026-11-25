package dev.doona.android.prototype.rv

import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.testing.Seed
import dev.doona.android.testing.seedColorScheme
import dev.doona.android.testing.setDoonaContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A predictive back gesture runs through the host and ends in a cancel, which leaves the stack, or a pop, which commits it. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xxhdpi")
class RvStackHostBackTest {
    @get:Rule
    val compose = createComposeRule()

    private var stack by mutableStateOf(listOf("activity", "settings"))
    private lateinit var dispatcher: OnBackPressedDispatcher
    private var composed = 0

    private fun event(progress: Float) = BackEventCompat(0f, 0f, progress, BackEventCompat.EDGE_LEFT)

    private fun host() = compose.setDoonaContent {
        RvTheme(seedColorScheme(Seed.Cyan.argb, false), false) {
            dispatcher = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            RvStackHost(stack, onBack = { stack = stack.dropLast(1) }, RvMotionOptions()) {
                SideEffect { composed++ }
                Box(Modifier.fillMaxSize())
            }
        }
    }

    @Test
    fun aCancelledGestureLeavesTheStackAndACompletedOneCommitsIt() {
        host()
        compose.waitForIdle()
        compose.runOnIdle { dispatcher.dispatchOnBackStarted(event(0f)) }
        listOf(0.2f, 0.5f, 0.8f).forEach { p -> compose.runOnIdle { dispatcher.dispatchOnBackProgressed(event(p)) } }
        compose.waitForIdle()
        compose.runOnIdle { dispatcher.dispatchOnBackCancelled() }
        compose.waitForIdle()
        assertEquals(listOf("activity", "settings"), stack)
        compose.runOnIdle { dispatcher.dispatchOnBackStarted(event(0f)) }
        compose.runOnIdle { dispatcher.dispatchOnBackProgressed(event(0.6f)) }
        compose.waitForIdle()
        compose.runOnIdle { dispatcher.dispatchOnBackProgressed(event(0.7f)) }
        compose.runOnIdle { dispatcher.dispatchOnBackProgressed(event(0.8f)) }
        compose.waitForIdle()
        compose.runOnIdle { dispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals(listOf("activity"), stack)
        assertTrue(composed > 0)
    }
}
