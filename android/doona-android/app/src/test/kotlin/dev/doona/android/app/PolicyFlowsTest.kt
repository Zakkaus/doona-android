package dev.doona.android.app

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
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
import dev.doona.android.vpn.FakeHonk
import dev.doona.android.vpn.HonkFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The policies and group pages as a user moves through them, in the shell, and when the first run's welcome page takes
 * 活動's place. The pages read honk, the app's RV sample profile ([DoonaTestApplication]), off the main thread.
 */
@RunWith(AndroidJUnit4::class)
class PolicyFlowsTest {
    @get:Rule
    val compose = createComposeRule()

    /** 活動's round connect or disconnect action, by its label. */
    private fun roundAction(label: String) = compose.onNode(hasText(label) and hasClickAction())

    private fun show(engine: Engine, vararg start: NavKey) {
        compose.setContent { SeedTheme { DoonaApp(engine, FakeInstalledApps(), onConnect = {}, start = start.toList().ifEmpty { listOf(ActivityKey) }) } }
    }

    /** The welcome page's title: the first run's page in place of 活動. */
    private val welcome = ApplicationProvider.getApplicationContext<Context>().getString(R.string.rv_welcome_title)

    /** Whether the welcome page is drawn; its title is both the large title and the bar's. */
    private fun welcomeShown() = compose.onAllNodesWithText(welcome).fetchSemanticsNodes().isNotEmpty()

    private fun shows(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    /** honk as the app reads it. */
    private val honk get() = ApplicationProvider.getApplicationContext<DoonaApplication>().container.nativeApi as FakeHonk

    /** honk with no node: with no subscription either, the first run's welcome page shows. */
    private fun noNodes() = honk.on("GET", "/api/v1/nodes", 200, NO_NODES)

    private val url = "https://example.invalid/x"

    private fun string(id: Int, vararg args: Any): String = ApplicationProvider.getApplicationContext<Context>().getString(id, *args)

    /** Types [url] into 新增訂閱's link field, the first field that takes text. */
    private fun typeUrl() {
        compose.onAllNodes(hasSetTextAction() and isEnabled())[0].performTextInput(url)
    }

    @Test
    fun `the first run's welcome page leads to 新增訂閱, and the first import hands over to 活動`() {
        noNodes()
        show(FakeEngine(subscriptions = emptyList()))
        compose.waitUntil(READ_MS) { welcomeShown() }
        roundAction(CONNECT).assertDoesNotExist()
        compose.onNodeWithText("Connected", substring = true).assertDoesNotExist()

        compose.onNodeWithText(string(R.string.rv_add_subscription)).performClick()
        typeUrl()
        // honk publishes the new subscription's nodes as it adds it.
        honk.on("GET", "/api/v1/nodes", 200, HonkFixtures.body("nodes/rv-sample"))
        compose.onNodeWithText(string(R.string.rv_add_import)).performClick()
        compose.waitUntil(READ_MS) { shows(string(R.string.rv_done)) }
        compose.onNodeWithText(string(R.string.rv_done)).performClick()

        compose.waitUntil(READ_MS) { !welcomeShown() }
        roundAction(CONNECT).assertExists()
    }

    @Test
    fun `a tapped node is chosen in its group`() {
        val honk = honk.apply { on("PUT", SELECTION, 200, "{}") }
        show(FakeEngine(), ActivityKey, PoliciesKey, GroupKey("g-Auto"))
        // The row's own click, not a tap at its centre, which a scroll can leave under the floating 測試群組.
        compose.waitUntil(READ_MS) { shows("Hong Kong 03") }
        compose.onNodeWithText("Hong Kong 03").performSemanticsAction(SemanticsActions.OnClick)
        // On an automatic group the choice is a pin, for both networks while 分開選擇 is off.
        val selections = { synchronized(honk) { honk.calls.filter { it.method == "PUT" && it.path == SELECTION }.map { it.body } } }
        compose.waitUntil(READ_MS) { selections().isNotEmpty() }
        assertEquals(listOf("""{"member_id":"n-0001939000000000","network":"both"}"""), selections())
    }

    @Test
    fun `a group page shows its name and a way back until honk has answered`() {
        // honk answers no read while the test holds it (FakeHonk.call is synchronized), so the group is not known yet.
        val honk = honk
        synchronized(honk) {
            show(FakeEngine(), ActivityKey, PoliciesKey, GroupKey("g-Proxy"))
            compose.onNodeWithContentDescription("Back").assertExists()
            compose.onNodeWithText("Test this group").assertDoesNotExist()
        }
        compose.waitUntil(READ_MS) { shows("Test this group") }
    }

    @Test
    fun `removing the last subscription while a tunnel runs keeps its status and the action that stops it, and the first-run page returns once it stops`() {
        val engine = FakeEngine(status = Status.Running(sinceEpochMs = 0))
        noNodes()
        show(engine)
        compose.runOnIdle { engine.subscriptions.value = emptyList() }

        assertFalse("no welcome page", welcomeShown())
        // 活動 reads its state off the main thread, which an idle main looper does not wait for.
        compose.waitUntil(READ_MS) { shows("No subscription yet") }
        compose.onNodeWithText("Connected", substring = true).assertExists()
        roundAction(DISCONNECT).assertIsEnabled()

        compose.runOnIdle { engine.disconnect() }
        compose.waitUntil(READ_MS) { welcomeShown() }
        roundAction(CONNECT).assertDoesNotExist()
        roundAction(DISCONNECT).assertDoesNotExist()
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
        noNodes()
        compose.setContent { SeedTheme { DoonaApp(engine, FakeInstalledApps(), onConnect = { starts++ }) } }
        compose.runOnIdle { fake.subscriptions.value = emptyList() }

        // The round action stops the tunnel; a tap on it is the user's Stop.
        roundAction(CONNECT).assertDoesNotExist()
        roundAction(DISCONNECT).performClick()
        compose.runOnIdle { assertEquals(Status.Stopping, fake.status.value) }

        // While it stops, the action stays, busy and disabled, and nothing offers to start.
        assertFalse("no welcome page", welcomeShown())
        roundAction(CONNECT).assertDoesNotExist()
        roundAction(DISCONNECT).assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals("no start was requested", 0, starts) }
        compose.runOnIdle { assertEquals(Status.Stopping, fake.status.value) }

        compose.runOnIdle { fake.status.value = Status.Stopped }
        compose.waitUntil(READ_MS) { welcomeShown() }
    }

    private companion object {
        const val CONNECT = "Connect"
        const val DISCONNECT = "Disconnect"
        const val SELECTION = "/api/v1/groups/g-Auto/selection"
        const val NO_NODES = """{"nodes":[],"next_cursor":null}"""
    }
}

private const val READ_MS = 5_000L
