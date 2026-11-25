package dev.doona.android.feature.nodes

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.designsystem.rv.RvPullMotion
import dev.doona.android.designsystem.rv.RvPullState
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.designsystem.rv.rememberRvPullState
import dev.doona.android.designsystem.rv.rvSystemScheme
import dev.doona.android.engine.Engine
import dev.doona.android.engine.Group
import dev.doona.android.engine.GroupKind
import dev.doona.android.engine.Member
import dev.doona.android.engine.Network
import dev.doona.android.engine.Subscription
import dev.doona.android.engine.fake.FakeEngine
import dev.doona.android.engine.fake.FakeRuleMode
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/** The 節點 routes as a user drives them: the real ViewModel and pages on the fake engine, as 活動's entry hosts them. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w412dp-h915dp")
class NodesRouteTest {
    @get:Rule
    val compose = createComposeRule()

    private val selected = CopyOnWriteArrayList<Triple<String, String?, Network?>>()

    private fun engine(): Engine {
        val sub = Subscription("s-1", "Harbor", "https://sub.example.net/token", nodes = 3, updatedAtEpochMs = 1L)
        val members = listOf(
            Member("n-jp", "🇯🇵 Tokyo 01", subscriptionId = "s-1"),
            Member("n-odd", "🦄 Osaka 02", subscriptionId = "s-1"),
            Member("n-apple", "🍎", subscriptionId = "s-1"),
        )
        val fake = FakeEngine(groups = listOf(Group(MAIN, "Proxy", GroupKind.Selector, members, Group.Selection("n-jp", pinned = true))), subscriptions = listOf(sub))
        return object : Engine by fake {
            override suspend fun select(groupId: String, memberId: String?, network: Network?): Result<Unit> {
                selected += Triple(groupId, memberId, network)
                return fake.select(groupId, memberId, network)
            }
        }
    }

    private fun viewModel(engine: Engine) = NodesViewModel(engine, Dispatchers.Unconfined, CoreNodePlaces, clock = { NOW })

    @Test
    fun `the picker draws a recognised emoji as our disc and keeps an unknown one, and a pick switches and closes`() {
        val vm = viewModel(engine())
        var pull: RvPullState? = null
        var drawn = 0
        compose.setContent {
            RvTheme(rvSystemScheme(false), false) {
                val state = rememberRvPullState(open = true).also { pull = it }
                NodesPull(vm, RvPullMotion.Sheet, state, onDetail = {}) {
                    // 活動's own state, which must outlive the picker covering it.
                    val kept = remember { ++drawn }
                    BasicText("activity $kept")
                }
            }
        }
        compose.waitForIdle()
        // Flag emoji leave the name (the disc draws our flag); an emoji the library does not know stays.
        compose.onNodeWithText("Tokyo 01").assertExists()
        compose.onNodeWithText("🇯🇵 Tokyo 01").assertDoesNotExist()
        compose.onNodeWithText("🦄 Osaka 02").assertExists()
        // A name of nothing but a mark reads as what the mark names.
        compose.onNodeWithText("Apple").assertExists()

        compose.onNodeWithText("🦄 Osaka 02").performClick()
        compose.waitUntil(5_000) { selected.isNotEmpty() && pull?.open == false }
        compose.waitForIdle()
        assertEquals(listOf(Triple(MAIN, "n-odd", null)), selected.toList())
        assertEquals("n-odd", vm.ui.value.state.currentId)
        // 活動 was composed once: the picker covered it without taking its state.
        compose.onNodeWithText("activity 1").assertExists()
        assertEquals(1, drawn)
    }

    @Test
    fun `search filters by the raw name and a pick makes the node the main one and closes`() {
        val vm = viewModel(engine())
        var closed = false
        compose.setContent { RvTheme(rvSystemScheme(false), false) { NodeSearchRoute(vm, onClose = { closed = true }, onDetail = {}) } }
        compose.onNode(hasSetTextAction()).performTextInput("osaka")
        compose.waitForIdle()
        compose.onNodeWithText("Tokyo 01").assertDoesNotExist()
        compose.onNodeWithText("🦄 Osaka 02").performClick()
        compose.waitUntil(5_000) { selected.isNotEmpty() }
        assertEquals(listOf(Triple(MAIN, "n-odd", null)), selected.toList())
        assertTrue(closed)
    }

    @Test
    fun `a node's page keeps the raw name`() {
        val vm = viewModel(engine())
        var back = false
        compose.setContent { RvTheme(rvSystemScheme(false), false) { NodeRoute(vm, "n-jp", onBack = { back = true }) } }
        compose.waitForIdle()
        compose.onAllNodesWithText("🇯🇵 Tokyo 01", useUnmergedTree = true).onFirst().assertExists()
        compose.onAllNodesWithText("Tokyo 01").assertCountEquals(0)
        assertFalse(back)
    }

    @Test
    fun `with no main group the pages say so and the node's page cannot make one main`() {
        val sub = Subscription("s-1", "Harbor", "https://sub.example.net/token", nodes = 1, updatedAtEpochMs = 1L)
        // A hand-edited profile: one group of the user's own, no Proxy, so the catalog's main group is missing.
        val mine = Group("g-Mine", "Mine", GroupKind.Selector, listOf(Member("n-jp", "Tokyo 01", subscriptionId = "s-1")), Group.Selection("n-jp", pinned = true))
        val vm = viewModel(FakeEngine(groups = listOf(mine), subscriptions = listOf(sub)))
        var node by mutableStateOf(false)
        compose.setContent {
            RvTheme(rvSystemScheme(false), false) {
                if (node) NodeRoute(vm, "n-jp", onBack = {}) else NodesRoute(vm, onBack = {}, onOpen = {}, onAllNodes = {}, onAdd = {})
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText(NO_MAIN).assertExists()
        compose.onAllNodesWithText("Tokyo 01").onFirst().assertExists()
        node = true
        compose.waitForIdle()
        compose.onNodeWithText("Set as main node").assertIsNotEnabled()
    }

    private companion object {
        const val NO_MAIN = "The profile has no main group, so there is no main node"
        const val MAIN = FakeRuleMode.MAIN_GROUP_ID
        const val NOW = 1_764_000_000_000L
    }
}
