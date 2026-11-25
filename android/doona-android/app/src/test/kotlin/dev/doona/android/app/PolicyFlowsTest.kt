package dev.doona.android.app

import android.content.Context
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.navigation3.runtime.NavKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.R
import dev.doona.android.engine.Engine
import dev.doona.android.engine.Status
import dev.doona.android.engine.fake.FakeEngine
import dev.doona.android.engine.fake.FakeInstalledApps
import dev.doona.android.testing.SeedTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The policies and group pages as a user moves through them, in the shell, and when the first run's welcome page takes
 * 活動's place.
 */
@RunWith(AndroidJUnit4::class)
class PolicyFlowsTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(engine: Engine, vararg start: NavKey) {
        compose.setContent { SeedTheme { DoonaApp(engine, FakeInstalledApps(), onConnect = {}, start = start.toList().ifEmpty { listOf(ActivityKey) }) } }
    }

    /** The welcome page's title: the first run's page in place of 活動. */
    private val welcome = ApplicationProvider.getApplicationContext<Context>().getString(R.string.rv_welcome_title)

    /** Whether the welcome page is drawn; its title is both the large title and the bar's. */
    private fun welcomeShown() = compose.onAllNodesWithText(welcome).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `a chip for a group inside opens that group's page, and back returns to the parent`() {
        show(FakeEngine(), ActivityKey, PoliciesKey, GroupKey("g-Proxy"))
        compose.onNodeWithText("Groups inside").assertExists()

        compose.onAllNodes(hasText("Auto") and hasClickAction())[0].performClick()
        compose.onNodeWithText("Groups inside").assertDoesNotExist()
        compose.onNodeWithText("Auto").assertExists()
        compose.onNodeWithText("Test this group").assertExists()

        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Groups inside").assertExists()
        compose.onNodeWithText("Proxy").assertExists()
    }

    @Test
    fun `a tapped member is pinned in its group`() {
        val engine = FakeEngine()
        show(engine, ActivityKey, PoliciesKey, GroupKey("g-Auto"))
        compose.onNodeWithText("jp-01").performClick()
        compose.runOnIdle {
            val auto = engine.groups.value.single { it.id == "g-Auto" }
            assertEquals("n-85d011c7e5f60718", auto.selectedId)
            assertTrue(auto.pinned)
        }
        compose.onNodeWithText("Return to automatic").assertExists()
    }

    @Test
    fun `a group page waits with progress and a way back until the engine has loaded`() {
        // As the real engine is before it has loaded: no groups yet, so the one the page names is not known to exist.
        val engine = FakeEngine(groups = emptyList()).apply { ready.value = false }
        show(engine, ActivityKey, PoliciesKey, GroupKey("g-Proxy"))
        compose.onNodeWithContentDescription("Back").assertExists()
        compose.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertExists()

        compose.runOnIdle {
            engine.groups.value = FakeEngine.SAMPLE_GROUPS
            engine.ready.value = true
        }
        compose.onNodeWithText("Groups inside").assertExists()
    }

    @Test
    fun `removing the last subscription while a tunnel runs keeps its status and switch, and the page returns once it stops`() {
        val engine = FakeEngine(status = Status.Running(sinceEpochMs = 0))
        show(engine)
        compose.runOnIdle { engine.subscriptions.value = emptyList() }

        assertFalse("no welcome page", welcomeShown())
        compose.onNodeWithText("Connected").assertExists()
        assertTrue("the switch that stops it", compose.onAllNodes(isToggleable()).fetchSemanticsNodes().isNotEmpty())

        compose.runOnIdle { engine.disconnect() }
        assertTrue("the welcome page", welcomeShown())
        compose.onAllNodes(isToggleable()).assertCountEquals(0)
    }

    @Test
    fun `with no subscription a tunnel that is stopping offers no way to start`() {
        val fake = FakeEngine(status = Status.Running(sinceEpochMs = 0))
        val engine = object : Engine by fake {
            // The engine takes a moment to stop: it holds Stopping until the test lets it go.
            override fun disconnect() {
                fake.status.value = Status.Stopping
            }
        }
        var starts = 0
        compose.setContent { SeedTheme { DoonaApp(engine, FakeInstalledApps(), onConnect = { starts++ }) } }
        compose.runOnIdle { fake.subscriptions.value = emptyList() }

        // The one switch is the one that stops the tunnel; a tap on it is the user's Stop.
        compose.onAllNodes(isToggleable()).assertCountEquals(1)
        compose.onAllNodes(isToggleable())[0].performClick()
        compose.runOnIdle { assertEquals(Status.Stopping, fake.status.value) }

        assertFalse("no welcome page", welcomeShown())
        compose.onAllNodes(isToggleable()).assertCountEquals(0)
        compose.runOnIdle { assertEquals("no start was requested", 0, starts) }

        compose.runOnIdle { fake.status.value = Status.Stopped }
        assertTrue("the welcome page", welcomeShown())
    }
}
