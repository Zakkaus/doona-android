package dev.doona.android.feature.nodes

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
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
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.Group
import dev.doona.android.engine.GroupKind
import dev.doona.android.engine.Member
import dev.doona.android.engine.Network
import dev.doona.android.engine.NodeInfo
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
import java.util.concurrent.atomic.AtomicInteger

/** The 節點 routes as a user drives them: the real ViewModel and pages on the fake engine, as 活動's entry hosts them. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w412dp-h915dp")
class NodesRouteTest {
    @get:Rule
    val compose = createComposeRule()

    private val selected = CopyOnWriteArrayList<Triple<String, String?, Network?>>()

    /** Picks that fail with Busy before the core switches; a latency test that fails. */
    private val busyPicks = AtomicInteger()
    private var testsFail = false

    private fun engine(extra: List<Member> = emptyList(), udp: String? = null): Engine {
        val sub = Subscription("s-1", "Harbor", "https://sub.example.net/token", nodes = 3, updatedAtEpochMs = 1L)
        val members = listOf(
            Member("n-jp", "🇯🇵 Tokyo 01", subscriptionId = "s-1"),
            Member("n-odd", "🦄 Osaka 02", subscriptionId = "s-1"),
            Member("n-apple", "🍎", subscriptionId = "s-1"),
        ) + extra
        val tcp = Group.Selection("n-jp", pinned = true)
        val main = Group(MAIN, "Proxy", GroupKind.Selector, members, tcp, udp?.let { Group.Selection(it, pinned = true) } ?: tcp)
        val fake = FakeEngine(groups = listOf(main), subscriptions = listOf(sub))
        return object : Engine by fake {
            override suspend fun select(groupId: String, memberId: String?, network: Network?): Result<Unit> {
                selected += Triple(groupId, memberId, network)
                if (busyPicks.getAndUpdate { (it - 1).coerceAtLeast(0) } > 0) return Result.failure(EngineException(ErrorCode.Busy))
                return fake.select(groupId, memberId, network)
            }

            override suspend fun testLatency(groupId: String?): Result<Unit> =
                if (testsFail) Result.failure(EngineException(ErrorCode.Internal)) else fake.testLatency(groupId)

            // A node the profile lists itself whose details the core has not sent yet.
            override suspend fun nodes(ids: List<String>?): Result<List<NodeInfo>> = fake.nodes(ids).map { all -> all.filter { it.id != "n-mine" } }
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
        compose.waitUntil(5_000) { closed }
        assertEquals(listOf(Triple(MAIN, "n-odd", null)), selected.toList())
    }

    @Test
    fun `a pick the core refuses keeps the picker open under its banner, and 重試 switches and closes`() {
        busyPicks.set(1)
        val vm = viewModel(engine())
        var pull: RvPullState? = null
        compose.setContent {
            RvTheme(rvSystemScheme(false), false) {
                NodesPull(vm, RvPullMotion.Sheet, rememberRvPullState(open = true).also { pull = it }, onDetail = {}) {}
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("🦄 Osaka 02").performClick()
        compose.waitUntil(5_000) { vm.ui.value.failure != null }
        compose.waitForIdle()
        assertEquals(true, pull?.open)
        compose.onNodeWithText("Could not switch the node").assertExists()
        assertEquals("n-jp", vm.ui.value.state.currentId)

        compose.onNodeWithText("Try again").performClick()
        compose.waitUntil(5_000) { pull?.open == false }
        assertEquals(listOf(Triple(MAIN, "n-odd", null), Triple(MAIN, "n-odd", null)), selected.toList())
        assertEquals(null, vm.ui.value.failure)
    }

    @Test
    fun `the main node's page shows a failed test and a failed pick`() {
        testsFail = true
        busyPicks.set(1)
        val vm = viewModel(engine())
        compose.setContent { RvTheme(rvSystemScheme(false), false) { MainNodeRoute(vm, onBack = {}, onSearch = {}, onAdd = {}) } }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Test latency").performClick()
        compose.waitUntil(5_000) { vm.ui.value.failure != null }
        compose.onNodeWithText("Could not test latency").assertExists()
        compose.onNodeWithText("🦄 Osaka 02").performClick()
        compose.waitUntil(5_000) { vm.ui.value.failure?.action == NodesAction.Select }
        compose.onNodeWithText("Could not switch the node").assertExists()
    }

    @Test
    fun `a node of no subscription is listed under 組態內 in the picker, and the 訂閱 chip can pick it`() {
        val vm = viewModel(engine(extra = listOf(Member("n-mine", "Home 01"))))
        compose.setContent { RvTheme(rvSystemScheme(false), false) { NodeSearchRoute(vm, onClose = {}, onDetail = {}) } }
        compose.waitForIdle()
        compose.onNodeWithText("In configuration").assertExists()
        compose.onNodeWithText("Home 01").assertExists()
        // Its details are not read yet (no protocol): its line is the place alone, not 「 · Other」.
        compose.onAllNodesWithText("Other", useUnmergedTree = true).onFirst().assertExists()
        compose.onAllNodesWithText(" · Other", useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithText("All subscriptions").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText("In configuration").onLast().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Home 01").assertExists()
        compose.onNodeWithText("Tokyo 01").assertDoesNotExist()
    }

    @Test
    fun `節點's 訂閱 chip shows one subscription's nodes and its 排序 chip orders them`() {
        val harbor = Subscription("s-1", "Harbor", "https://sub.example.net/a", nodes = 2, updatedAtEpochMs = 1L)
        val bay = Subscription("s-2", "Bay", "https://sub.example.net/b", nodes = 1, updatedAtEpochMs = 1L)
        val members = listOf(Member("n-jp", "Tokyo 01", 20, subscriptionId = "s-1"), Member("n-os", "Osaka 01", 30, subscriptionId = "s-1"), Member("n-sg", "Singapore 01", 10, subscriptionId = "s-2"))
        val main = Group(MAIN, "Proxy", GroupKind.Selector, members, Group.Selection("n-jp", pinned = true))
        val vm = viewModel(FakeEngine(groups = listOf(main), subscriptions = listOf(harbor, bay)))
        compose.setContent { RvTheme(rvSystemScheme(false), false) { NodesRoute(vm, onBack = {}, onOpen = {}, onAllNodes = {}, onAdd = {}) } }
        compose.waitForIdle()
        compose.onAllNodesWithText("Singapore 01").onFirst().assertExists()
        compose.onNodeWithText("All subscriptions").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Harbor").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText("Singapore 01").assertCountEquals(0)
        assertEquals(listOf("Tokyo 01", "Osaka 01"), rowNames())
        compose.onNodeWithText("By latency").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Name").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("By name").assertExists()
        assertEquals(listOf("Osaka 01", "Tokyo 01"), rowNames())
    }

    /** The node rows' names, top to bottom. */
    private fun rowNames(): List<String> {
        val names = setOf("Tokyo 01", "Osaka 01")
        return compose.onAllNodes(hasText("01", substring = true), useUnmergedTree = true).fetchSemanticsNodes()
            .sortedBy { it.positionInRoot.y }
            .mapNotNull { node -> node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }?.takeIf { it in names } }
            .distinct()
    }

    @Test
    fun `with the networks apart the picker checks both nodes and 套用到 sends a pick to one network`() {
        val vm = viewModel(engine(udp = "n-odd"))
        compose.setContent { RvTheme(rvSystemScheme(false), false) { MainNodeRoute(vm, onBack = {}, onSearch = {}, onAdd = {}) } }
        compose.waitForIdle()
        assertEquals(setOf("n-jp", "n-odd"), vm.ui.value.state.current)
        compose.onNodeWithText("Apply to").assertExists()
        compose.onNodeWithText("UDP").performClick()
        compose.onNodeWithText("Apple").performClick()
        compose.waitUntil(5_000) { selected.isNotEmpty() }
        assertEquals(listOf(Triple(MAIN, "n-apple", Network.Udp)), selected.toList())
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
    fun `with no main group the pages show no main node and the node's page cannot make one main`() {
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
        // As 活動 shows no main node as a dash (q-p3a-nomain): no line under the title, and no node checked.
        compose.onNodeWithText(HINT).assertDoesNotExist()
        compose.onAllNodesWithText("Tokyo 01").onFirst().assertExists()
        node = true
        compose.waitForIdle()
        compose.onNodeWithText("Set as main node").assertIsNotEnabled()
    }

    private companion object {
        const val HINT = "Your pick becomes the main node"
        const val MAIN = FakeRuleMode.MAIN_GROUP_ID
        const val NOW = 1_764_000_000_000L
    }
}
