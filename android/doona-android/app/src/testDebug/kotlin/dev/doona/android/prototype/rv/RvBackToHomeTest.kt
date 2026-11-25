package dev.doona.android.prototype.rv

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** Back from a tab home: 活動 exits, every other tab goes to 活動, and a stacked page pops first. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RvBackToHomeTest {
    @get:Rule
    val compose = createComposeRule()

    private val tabs = setOf("activity", "policy", "connections", "settings")

    private var stack by mutableStateOf(listOf("activity"))
    private lateinit var dispatcher: OnBackPressedDispatcher

    private fun host(start: List<String>) {
        stack = start
        compose.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            RvBackToHome(stack, "activity", tabs) { stack = listOf("activity") }
        }
        compose.waitForIdle()
    }

    @Test
    fun aTabOtherThanActivityGoesToActivity() {
        host(listOf("policy"))
        listOf("policy", "connections", "settings").forEach { tab ->
            compose.runOnIdle { stack = listOf(tab) }
            compose.waitForIdle()
            assertTrue("$tab handles back", dispatcher.hasEnabledCallbacks())
            compose.runOnIdle { dispatcher.onBackPressed() }
            compose.waitForIdle()
            assertEquals("$tab goes to activity", listOf("activity"), stack)
            assertFalse("activity leaves back to the system", dispatcher.hasEnabledCallbacks())
        }
    }

    @Test
    fun activityLeavesBackToTheSystem() {
        host(listOf("activity"))
        assertFalse(dispatcher.hasEnabledCallbacks())
        assertEquals(listOf("activity"), stack)
    }

    @Test
    fun aPageStackedOnATabIsLeftToTheHostsPop() {
        host(listOf("settings", "theme"))
        assertFalse(dispatcher.hasEnabledCallbacks())
    }
}
