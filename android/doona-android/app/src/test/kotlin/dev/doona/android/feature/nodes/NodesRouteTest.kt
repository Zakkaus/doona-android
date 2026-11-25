package dev.doona.android.feature.nodes

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.designsystem.rv.RvPullMotion
import dev.doona.android.designsystem.rv.RvPullState
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.designsystem.rv.rememberRvPullState
import dev.doona.android.designsystem.rv.rvSystemScheme
import dev.doona.android.engine.Catalog
import dev.doona.android.engine.Engine
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.Group
import dev.doona.android.engine.GroupKind
import dev.doona.android.engine.Member
import dev.doona.android.engine.Network
import dev.doona.android.engine.NodeInfo
import dev.doona.android.engine.ShareLink
import dev.doona.android.engine.Subscription
import dev.doona.android.engine.SubscriptionFetch
import dev.doona.android.engine.SubscriptionFormat
import dev.doona.android.engine.fake.FakeEngine
import dev.doona.android.engine.fake.FakeRuleMode
import dev.doona.android.feature.model.CoreNodePlaces
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
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

    @Volatile private var readsFail = false

    private val tested = CopyOnWriteArrayList<String?>()
    private val updated = CopyOnWriteArrayList<String>()

    private fun engine(extra: List<Member> = emptyList(), udp: String? = null, format: SubscriptionFormat = SubscriptionFormat.ShareLinks): Engine {
        val sub = Subscription("s-1", "Harbor", "https://sub.example.net/token", nodes = 3, lastFetch = SubscriptionFetch(1L, format))
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

            override suspend fun testLatency(groupId: String?): Result<Unit> {
                tested += groupId
                return if (testsFail) Result.failure(EngineException(ErrorCode.Internal)) else fake.testLatency(groupId)
            }

            override suspend fun nodeShareLink(nodeId: String): Result<ShareLink> = Result.success(ShareLink("vless://$nodeId@example.net:443"))

            override suspend fun updateSubscription(id: String): Result<Subscription> {
                updated += id
                return fake.updateSubscription(id)
            }

            // A node the profile lists itself whose details the core has not sent yet.
            override suspend fun nodes(ids: List<String>?): Result<List<NodeInfo>> =
                if (readsFail) Result.failure(EngineException(ErrorCode.Busy)) else fake.nodes(ids).map { all -> all.filter { it.id != "n-mine" } }
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
                NodesPull(vm, RvPullMotion.Sheet, state, onDetail = {}, onAdd = {}) {
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
        compose.setContent { RvTheme(rvSystemScheme(false), false) { NodeSearchRoute(vm, onClose = { closed = true }, onDetail = {}, onAdd = {}) } }
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
                NodesPull(vm, RvPullMotion.Sheet, rememberRvPullState(open = true).also { pull = it }, onDetail = {}, onAdd = {}) {}
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
        compose.setContent { RvTheme(rvSystemScheme(false), false) { NodeSearchRoute(vm, onClose = {}, onDetail = {}, onAdd = {}) } }
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
        val harbor = Subscription("s-1", "Harbor", "https://sub.example.net/a", nodes = 2, lastFetch = SubscriptionFetch(1L, SubscriptionFormat.Clash))
        val bay = Subscription("s-2", "Bay", "https://sub.example.net/b", nodes = 1, lastFetch = SubscriptionFetch(1L, SubscriptionFormat.Clash))
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

    /** 依名稱 orders by the name the row draws (decision 2026-11-25 19:25): a name of a recognised emoji alone reads as its mark's name. */
    @Test
    fun `the picker's 依名稱 sorts a name of a mark alone by what the row draws`() {
        val vm = viewModel(engine())
        compose.setContent { RvTheme(rvSystemScheme(false), false) { NodesPull(vm, RvPullMotion.Sheet, rememberRvPullState(open = true), onDetail = {}, onAdd = {}) {} } }
        compose.waitForIdle()
        compose.onNodeWithText("By latency").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Name").performClick()
        compose.waitForIdle()
        // 🍎 is drawn as Apple, so it sorts as Apple; 🦄 is no mark of ours and stays in the name.
        assertEquals(listOf("Apple", "Tokyo 01", "🦄 Osaka 02"), rowNames(setOf("Apple", "Tokyo 01", "🦄 Osaka 02")))
    }

    /** The node's page tests that node alone, with one plan (the main group, which lists it), as its row's menu does. */
    @Test
    fun `the node's page 測試延遲 tests that node, not every group`() {
        val vm = viewModel(engine())
        compose.setContent { RvTheme(rvSystemScheme(false), false) { NodeRoute(vm, "n-jp", onBack = {}) } }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Test latency").performClick()
        compose.waitUntil(5_000) { tested.isNotEmpty() }
        assertEquals(listOf<String?>(MAIN), tested.toList())
    }

    /** The node rows' names, top to bottom. */
    private fun rowNames(names: Set<String> = setOf("Tokyo 01", "Osaka 01")): List<String> =
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true).fetchSemanticsNodes()
            .sortedBy { it.positionInRoot.y }
            .mapNotNull { node -> node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }?.takeIf { it in names } }
            .distinct()

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
        val sub = Subscription("s-1", "Harbor", "https://sub.example.net/token", nodes = 1, lastFetch = SubscriptionFetch(1L, SubscriptionFormat.Clash))
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

    @Test
    fun `節點 checks both the TCP and the UDP node while the main group's networks are apart`() {
        val vm = viewModel(engine(udp = "n-odd"))
        compose.setContent { RvTheme(rvSystemScheme(false), false) { NodesRoute(vm, onBack = {}, onOpen = {}, onAllNodes = {}, onAdd = {}) } }
        compose.waitForIdle()
        val inUse = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "In use")
        val checked = compose.onAllNodes(inUse).fetchSemanticsNodes().map { it.config[SemanticsProperties.Text].joinToString("") { t -> t.text } }
        assertEquals(2, checked.size)
        assertTrue(checked.toString(), checked.any { "Tokyo 01" in it } && checked.any { "Osaka 02" in it })
    }

    @Test
    fun `a failed read of the nodes' details shows on 節點 and the node's page with dashes, and 重試 reads them again`() {
        readsFail = true
        val vm = viewModel(engine())
        var node by mutableStateOf(false)
        compose.setContent {
            RvTheme(rvSystemScheme(false), false) {
                if (node) NodeRoute(vm, "n-jp", onBack = {}) else NodesRoute(vm, onBack = {}, onOpen = {}, onAllNodes = {}, onAdd = {})
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Could not read the nodes").assertExists()
        // The row's menu has no share link for a node the core has not described (no `://:0`).
        compose.onAllNodesWithText("Tokyo 01").onFirst().performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithText("Test latency").assertExists()
        compose.onNodeWithText("Copy share link").assertDoesNotExist()
        node = true
        compose.waitForIdle()
        compose.onNodeWithText("Could not read the nodes").assertExists()
        // Protocol, server, port, transport and TLS read as dashes, never a port 0 or TLS Off the core did not send.
        val dashes = compose.onAllNodesWithText("—", useUnmergedTree = true).fetchSemanticsNodes().size
        readsFail = false
        compose.onNodeWithText("Try again").performClick()
        compose.waitUntil(5_000) { vm.ui.value.failure == null }
        compose.waitForIdle()
        compose.onNodeWithText("Could not read the nodes").assertDoesNotExist()
        assertTrue(vm.ui.value.state.node("n-jp")!!.described)
        assertEquals(dashes - 5, compose.onAllNodesWithText("—", useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test
    fun `two subscriptions of one name stay apart in the 訂閱 menu, and one that gave no node is not offered`() {
        val first = Subscription("s-1", "Harbor", "https://sub.example.net/a", nodes = 1, lastFetch = SubscriptionFetch(1L, SubscriptionFormat.Clash))
        val second = Subscription("s-2", "Harbor", "https://sub.example.net/b", nodes = 1, lastFetch = SubscriptionFetch(1L, SubscriptionFormat.Clash))
        val failed = Subscription("s-3", "Lantern", "https://lantern.example.net/c", nodes = 0, lastFetch = SubscriptionFetch(1L, SubscriptionFormat.Clash))
        val members = listOf(Member("n-jp", "Tokyo 01", 20, subscriptionId = "s-1"), Member("n-os", "Osaka 01", 30, subscriptionId = "s-2"))
        val main = Group(MAIN, "Proxy", GroupKind.Selector, members, Group.Selection("n-jp", pinned = true))
        val vm = viewModel(FakeEngine(groups = listOf(main), subscriptions = listOf(first, second, failed)))
        compose.setContent { RvTheme(rvSystemScheme(false), false) { NodesRoute(vm, onBack = {}, onOpen = {}, onAllNodes = {}, onAdd = {}) } }
        compose.waitForIdle()
        compose.onNodeWithText("All subscriptions").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText("Harbor").assertCountEquals(2)
        compose.onNodeWithText("Lantern").assertDoesNotExist()
        compose.onAllNodesWithText("Harbor").onLast().performClick()
        compose.waitForIdle()
        assertEquals(listOf("Osaka 01"), rowNames())
    }

    @Test
    fun `a node of no subscription names 組態內 as its source on its page`() {
        val vm = viewModel(engine(extra = listOf(Member("n-mine", "Home 01"))))
        compose.setContent { RvTheme(rvSystemScheme(false), false) { NodeRoute(vm, "n-mine", onBack = {}) } }
        compose.waitForIdle()
        compose.onNodeWithText("In configuration", useUnmergedTree = true).assertExists()
    }

    /** The app gives the rows' menus their actions (they were the sample's alone): 設為主節點, 測試延遲, 複製分享連結, 更新. */
    @Test
    fun `the rows' menus run the app's own actions`() {
        val vm = viewModel(engine())
        var pull by mutableStateOf(false)
        compose.setContent {
            RvTheme(rvSystemScheme(false), false) {
                if (pull) {
                    NodesPull(vm, RvPullMotion.Sheet, rememberRvPullState(open = true), onDetail = {}, onAdd = {}) {}
                } else {
                    NodesRoute(vm, onBack = {}, onOpen = {}, onAllNodes = {}, onAdd = {})
                }
            }
        }
        compose.waitForIdle()
        compose.onAllNodesWithText("🦄 Osaka 02").onFirst().performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithText("Set as main node").performClick()
        compose.waitUntil(5_000) { selected.isNotEmpty() }
        assertEquals(listOf(Triple(MAIN, "n-odd", null)), selected.toList())
        compose.waitForIdle()
        compose.onAllNodesWithText("Tokyo 01").onFirst().performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithText("Test latency").performClick()
        compose.waitUntil(5_000) { tested.isNotEmpty() }
        assertEquals(listOf<String?>(MAIN), tested.toList())
        compose.waitForIdle()
        compose.onAllNodesWithText("Tokyo 01").onFirst().performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithText("Copy share link").performClick()
        compose.waitForIdle()
        val clipboard = ApplicationProvider.getApplicationContext<Context>().getSystemService(ClipboardManager::class.java)
        assertEquals("vless://n-jp@example.net:443", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        // The picker's 訂閱 row: 更新 updates that subscription.
        pull = true
        compose.waitForIdle()
        compose.onAllNodesWithText("Harbor").onFirst().performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithText("Update").performClick()
        compose.waitUntil(5_000) { updated.isNotEmpty() }
        assertEquals(listOf("s-1"), updated.toList())
    }

    /** The core keeps a share link only for a share-link subscription's node (Engine.nodeShareLink): no other node offers one. */
    @Test
    fun `a node of a subscription that is not share links offers no share link`() {
        val vm = viewModel(engine(format = SubscriptionFormat.Clash))
        compose.setContent { RvTheme(rvSystemScheme(false), false) { NodesRoute(vm, onBack = {}, onOpen = {}, onAllNodes = {}, onAdd = {}) } }
        compose.waitForIdle()
        assertTrue(vm.ui.value.state.node("n-jp")!!.described)
        compose.onAllNodesWithText("Tokyo 01").onFirst().performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithText("Test latency").assertExists()
        compose.onNodeWithText("Copy share link").assertDoesNotExist()
    }

    @Test
    fun `the picker says when a search matches nothing and lists no subscription that gave no node`() {
        val empty = Subscription("s-2", "Lantern", "https://lantern.example.net/c", nodes = 0, lastFetch = SubscriptionFetch(1L, SubscriptionFormat.Clash))
        val fake = engine()
        val vm = viewModel(object : Engine by fake {
            override val subscriptions = MutableStateFlow(fake.subscriptions.value + empty)
            override val catalog = MutableStateFlow(fake.catalog.value?.let { it.copy(subscriptions = it.subscriptions + empty) })
        })
        compose.setContent { RvTheme(rvSystemScheme(false), false) { NodeSearchRoute(vm, onClose = {}, onDetail = {}, onAdd = {}) } }
        compose.waitForIdle()
        compose.onNodeWithText("Harbor").assertExists()
        compose.onNodeWithText("Lantern").assertDoesNotExist()
        compose.onNodeWithText("No matching nodes").assertDoesNotExist()
        compose.onNode(hasSetTextAction()).performTextInput("xyz")
        compose.waitForIdle()
        compose.onNodeWithText("No matching nodes").assertExists()
    }

    @Test
    fun `with no node the pull-down picker offers 新增訂閱 instead of chips over nothing`() {
        val vm = viewModel(FakeEngine(groups = listOf(Group(MAIN, "Proxy", GroupKind.Selector, emptyList(), Group.Selection(null, pinned = false)))))
        var added = false
        compose.setContent {
            RvTheme(rvSystemScheme(false), false) {
                NodesPull(vm, RvPullMotion.Sheet, rememberRvPullState(open = true), onDetail = {}, onAdd = { added = true }) {}
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("All subscriptions").assertDoesNotExist()
        compose.onNodeWithText("Add subscription").performClick()
        assertTrue(added)
    }

    /**
     * Before the catalog is first read (a cold start, a restore after process death), search, the main node's page and the
     * pull-down picker show the list's skeleton, not the empty state and its 新增訂閱; the nodes come with the catalog.
     */
    @Test
    fun `before the first read the pickers show no empty state`() {
        val fake = engine()
        val catalog = MutableStateFlow<Catalog?>(null)
        val vm = viewModel(object : Engine by fake {
            override val catalog = catalog
        })
        var page by mutableIntStateOf(0)
        compose.setContent {
            RvTheme(rvSystemScheme(false), false) {
                when (page) {
                    0 -> NodeSearchRoute(vm, onClose = {}, onDetail = {}, onAdd = {})
                    1 -> MainNodeRoute(vm, onBack = {}, onSearch = {}, onAdd = {})
                    else -> NodesPull(vm, RvPullMotion.Sheet, rememberRvPullState(open = true), onDetail = {}, onAdd = {}) {}
                }
            }
        }
        repeat(3) {
            page = it
            compose.waitForIdle()
            compose.onNodeWithText("Add subscription").assertDoesNotExist()
        }
        catalog.value = fake.catalog.value
        compose.waitForIdle()
        compose.onAllNodesWithText("Tokyo 01", substring = true).onFirst().assertExists()
    }

    /** 規則's 主節點 page carries every node picker's 訂閱 and 排序 chips (ruling q-p3c-mainchips), not the range row. */
    @Test
    fun `the main node's page filters by subscription with the pickers' chips`() {
        val harbor = Subscription("s-1", "Harbor", "https://sub.example.net/a", nodes = 1, lastFetch = SubscriptionFetch(1L, SubscriptionFormat.Clash))
        val bay = Subscription("s-2", "Bay", "https://sub.example.net/b", nodes = 1, lastFetch = SubscriptionFetch(1L, SubscriptionFormat.Clash))
        val members = listOf(Member("n-jp", "Tokyo 01", 20, subscriptionId = "s-1"), Member("n-sg", "Singapore 01", 10, subscriptionId = "s-2"))
        val main = Group(MAIN, "Proxy", GroupKind.Selector, members, Group.Selection("n-jp", pinned = true))
        val vm = viewModel(FakeEngine(groups = listOf(main), subscriptions = listOf(harbor, bay)))
        compose.setContent { RvTheme(rvSystemScheme(false), false) { MainNodeRoute(vm, onBack = {}, onSearch = {}, onAdd = {}) } }
        compose.waitForIdle()
        compose.onNodeWithText("Stability").assertDoesNotExist()
        compose.onNodeWithText("Tokyo 01").assertExists()
        compose.onNodeWithText("All subscriptions").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText("Bay").onLast().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Singapore 01").assertExists()
        compose.onNodeWithText("Tokyo 01").assertDoesNotExist()
    }

    private companion object {
        const val HINT = "Your pick becomes the main node"
        const val MAIN = FakeRuleMode.MAIN_GROUP_ID
        const val NOW = 1_764_000_000_000L
    }
}
