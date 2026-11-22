package dev.doona.android.app

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
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.engine.Engine
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.FailureReason
import dev.doona.android.engine.Status
import dev.doona.android.engine.Subscription
import dev.doona.android.engine.fake.FakeEngine
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The policies, group, subscriptions and first-run pages as a user moves through them, in the shell. */
@RunWith(AndroidJUnit4::class)
class PolicyFlowsTest {
    @get:Rule
    val compose = createComposeRule()

    private val url = "https://example.invalid/x"

    private fun show(engine: Engine, vararg start: NavKey) {
        compose.setContent { DoonaTheme { DoonaApp(engine, onConnect = {}, start = start.toList().ifEmpty { listOf(ActivityKey) }) } }
    }

    private fun typeUrl() {
        compose.onAllNodes(hasSetTextAction() and isEnabled())[0].performTextInput(url)
    }

    private fun shows(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

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
            assertEquals("n-jp1", auto.selectedId)
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
    fun `a subscription is added and removed from the subscriptions page`() {
        val engine = FakeEngine(subscriptions = emptyList())
        show(engine, ActivityKey, PoliciesKey, SubscriptionsKey)
        compose.onNodeWithText("No subscriptions").assertExists()

        compose.onNodeWithText("Add subscription").performClick()
        typeUrl()
        compose.onNodeWithText("Add").performClick()
        compose.onNodeWithText(url).assertExists()
        compose.runOnIdle { assertEquals(listOf(url), engine.subscriptions.value.map { it.url }) }

        compose.onNodeWithText("Remove").performClick()
        compose.onNodeWithText("Remove subscription?").assertExists()
        compose.runOnIdle { assertEquals(1, engine.subscriptions.value.size) }
        compose.onAllNodesWithText("Remove")[1].performClick()
        compose.onNodeWithText("No subscriptions").assertExists()
        compose.runOnIdle { assertTrue(engine.subscriptions.value.isEmpty()) }
    }

    @Test
    fun `first run offers no switch to start, and the first import hands over to the activity screen`() {
        val engine = FakeEngine(subscriptions = emptyList())
        show(engine)
        compose.onNodeWithText("Add your first subscription").assertExists()
        compose.onAllNodes(isToggleable()).assertCountEquals(0)
        compose.onNodeWithText("Connected").assertDoesNotExist()

        typeUrl()
        compose.onNodeWithText("Add").performClick()

        compose.onNodeWithText("Add your first subscription").assertDoesNotExist()
        assertTrue("the activity screen's switch", compose.onAllNodes(isToggleable()).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun `the hint to add a subscription shows only when the core had no node to start with`() {
        val engine = FakeEngine(subscriptions = emptyList(), status = Status.Failed(FailureReason.StartFailed, ErrorCode.NoNodes))
        show(engine)
        compose.onNodeWithText("Could not connect: there are no nodes. Add a subscription first.").assertExists()

        compose.runOnIdle { engine.status.value = Status.Failed(FailureReason.StartFailed) }
        compose.onNodeWithText("Could not connect: there are no nodes. Add a subscription first.").assertDoesNotExist()
        compose.onNodeWithText("The VPN did not start").assertExists()

        compose.runOnIdle { engine.status.value = Status.Failed(FailureReason.StartFailed, ErrorCode.Internal) }
        compose.onNodeWithText("Could not connect: there are no nodes. Add a subscription first.").assertDoesNotExist()
        compose.onNodeWithText("The VPN did not start").assertExists()
    }

    @Test
    fun `removing the last subscription while a tunnel runs keeps its status and switch, and the page returns once it stops`() {
        val engine = FakeEngine(status = Status.Running(sinceEpochMs = 0))
        show(engine)
        compose.runOnIdle { engine.subscriptions.value = emptyList() }

        compose.onNodeWithText("Add your first subscription").assertDoesNotExist()
        compose.onNodeWithText("Connected").assertExists()
        assertTrue("the switch that stops it", compose.onAllNodes(isToggleable()).fetchSemanticsNodes().isNotEmpty())

        compose.runOnIdle { engine.disconnect() }
        compose.onNodeWithText("Add your first subscription").assertExists()
        compose.onAllNodes(isToggleable()).assertCountEquals(0)
    }

    @Test
    fun `leaving the subscriptions page during a slow add and retrying imports once`() {
        val gate = CompletableDeferred<Unit>()
        val fake = FakeEngine(subscriptions = emptyList())
        var calls = 0
        val engine = object : Engine by fake {
            override suspend fun addSubscription(url: String, name: String?): Result<Subscription> {
                calls++
                gate.await()
                return fake.addSubscription(url, name)
            }
        }
        show(engine, ActivityKey, PoliciesKey, SubscriptionsKey)
        compose.onNodeWithText("Add subscription").performClick()
        typeUrl()
        compose.onNodeWithText("Add").performClick()
        compose.runOnIdle { assertEquals(1, calls) }

        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("Subscriptions").performClick()
        if (!shows("Add")) compose.onNodeWithText("Add subscription").performClick()
        compose.onAllNodes(hasSetTextAction() and isEnabled()).fetchSemanticsNodes().firstOrNull()?.let { typeUrl() }
        compose.onNodeWithText("Add").performClick()

        compose.runOnIdle { gate.complete(Unit) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("one fetch for the one import", 1, calls)
            assertEquals(1, fake.subscriptions.value.size)
        }
    }
}
