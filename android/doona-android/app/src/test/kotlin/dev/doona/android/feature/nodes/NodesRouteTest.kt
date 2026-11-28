package dev.doona.android.feature.nodes

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
import androidx.compose.ui.test.assertHasNoClickAction
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
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.designsystem.rv.RvPullMotion
import dev.doona.android.designsystem.rv.RvPullState
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.designsystem.rv.rememberRvPullState
import dev.doona.android.designsystem.rv.rvSystemScheme
import dev.doona.android.feature.model.CoreNodePlaces
import dev.doona.android.honk.NativeApi
import dev.doona.android.honk.NativeResponse
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

/** The 節點 routes as a user drives them: the real ViewModel and pages on a fake honk, as 活動's entry hosts them. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w412dp-h915dp")
class NodesRouteTest {
    @get:Rule
    val compose = createComposeRule()

    private class N(val id: String, val name: String, val provider: String? = "s-1", val latency: Int? = null, val protocol: String? = "vless")

    private class P(val id: String, val name: String, val nodes: Int)

    /**
     * honk as these pages read and write it: one group, [group], of every node, whose selection a pick moves; the routing's
     * fallback names it unless [fallback] names another. Picks that fail with Busy ([busyPicks]), probes that fail
     * ([testsFail]) and a failing read of the nodes ([readsFail]).
     */
    private inner class Honk(
        val nodes: List<N> = SAMPLE,
        val providers: List<P> = listOf(P("s-1", "Harbor", 3)),
        @Volatile var tcp: String? = nodes.firstOrNull()?.id,
        @Volatile var udp: String? = tcp,
        val group: String = MAIN,
        val name: String = "Proxy",
        val fallback: String = name,
    ) : NativeApi {
        override fun call(method: String, path: String, body: String?, headers: Map<String, String>): NativeResponse = when (method) {
            "GET" -> read(path)
            "PUT" -> if (path == "/api/v1/groups/$group/selection") select(Json.parseToJsonElement(body!!).jsonObject) else NativeResponse(404, ERROR)
            "POST" -> post(path, body)
            else -> NativeResponse(404, ERROR)
        }

        private fun read(path: String): NativeResponse = when (path) {
            "/api/v1/nodes" -> if (readsFail) NativeResponse(500, ERROR) else ok("""{"nodes":[${nodes.joinToString(",") { node(it) }}],"next_cursor":null}""")
            "/api/v1/providers" -> ok("""{"providers":[${providers.joinToString(",") { provider(it) }}],"next_cursor":null}""")
            "/api/v1/groups" -> ok("""[{"id":"$group","name":"$name"}]""")
            "/api/v1/groups/$group" -> ok(groupBody())
            "/api/v1/rules" -> ok("""{"generation_id":"1","rules":[],"fallback":{"outbound":"$fallback","source":null}}""")
            "/api/v1/operations/op-probe" -> ok(succeeded("{}"))
            "/api/v1/operations/op-refresh" -> ok(succeeded(provider(providers.first())))
            else -> NativeResponse(404, ERROR)
        }

        private fun post(path: String, body: String?): NativeResponse = when {
            path == "/api/v1/probes" -> {
                tested += Json.parseToJsonElement(body!!).jsonObject.getValue("target").toString()
                if (testsFail) NativeResponse(500, ERROR) else NativeResponse(202, accepted("op-probe"))
            }

            path.endsWith("/refresh") -> {
                updated += path.removePrefix("/api/v1/providers/").removeSuffix("/refresh")
                NativeResponse(202, accepted("op-refresh"))
            }

            else -> NativeResponse(404, ERROR)
        }

        private fun select(body: kotlinx.serialization.json.JsonObject): NativeResponse {
            val member = body.getValue("member_id").jsonPrimitive.content
            val network = body.getValue("network").jsonPrimitive.content
            selected += Triple(group, member, network)
            if (busyPicks.getAndUpdate { (it - 1).coerceAtLeast(0) } > 0) return NativeResponse(409, ERROR)
            if (network != "udp") tcp = member
            if (network != "tcp") udp = member
            return ok("{}")
        }

        private fun groupBody(): String {
            val members = nodes.joinToString(",") { """{"id":"${it.id}","name":"${it.name}","kind":"node"}""" }
            fun sel(id: String?) = id?.let { """{"member_id":"$it","resolved_leaf_node_id":"$it","source":"override"}""" } ?: "null"
            return """{"id":"$group","name":"$name","icon":null,"policy":{"kind":"selector","native":"selector"},"members":[$members],""" +
                """"runtime":{"selection":{"tcp":${sel(tcp)},"udp":${sel(udp)}},"health":[]},"capabilities":{"can_select":true,"can_override":false}}"""
        }
    }

    /** A dispatcher that holds what it is given until [release]: the first read waits, as before honk answers. */
    private class Held : CoroutineDispatcher() {
        private val held = mutableListOf<Runnable>()
        private var open = false

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (open) block.run() else held += block
        }

        fun release() {
            open = true
            held.toList().also { held.clear() }.forEach { it.run() }
        }
    }

    private val selected = CopyOnWriteArrayList<Triple<String, String, String>>()

    /** Picks that fail with Busy before honk switches; a latency test that fails. */
    private val busyPicks = AtomicInteger()

    @Volatile private var testsFail = false

    @Volatile private var readsFail = false

    private val tested = CopyOnWriteArrayList<String>()
    private val updated = CopyOnWriteArrayList<String>()

    private fun viewModel(api: NativeApi, io: CoroutineDispatcher = Dispatchers.Unconfined) = NodesViewModel(api, io, CoreNodePlaces, clock = { NOW }).also { it.load() }

    @Test
    fun `the picker draws a recognised emoji as our disc and keeps an unknown one, and a pick switches and closes`() {
        val vm = viewModel(Honk())
        var pull: RvPullState? = null
        var drawn = 0
        compose.setContent {
            RvTheme(rvSystemScheme(false), false) {
                val state = rememberRvPullState(open = true).also { pull = it }
                NodesPull(vm, RvPullMotion.Sheet, state, onDetail = {}, onAdd = {}, onAddNode = {}) {
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
        assertEquals(listOf(Triple(MAIN, "n-odd", "both")), selected.toList())
        assertEquals("n-odd", vm.ui.value.state.currentId)
        // 活動 was composed once: the picker covered it without taking its state.
        compose.onNodeWithText("activity 1").assertExists()
        assertEquals(1, drawn)
    }

    @Test
    fun `search filters by the raw name and a pick makes the node the main one and closes`() {
        val vm = viewModel(Honk())
        var closed = false
        compose.setContent { RvTheme(rvSystemScheme(false), false) { NodeSearchRoute(vm, onClose = { closed = true }, onDetail = {}, onAdd = {}, onAddNode = {}) } }
        compose.onNode(hasSetTextAction()).performTextInput("osaka")
        compose.waitForIdle()
        compose.onNodeWithText("Tokyo 01").assertDoesNotExist()
        compose.onNodeWithText("🦄 Osaka 02").performClick()
        compose.waitUntil(5_000) { closed }
        assertEquals(listOf(Triple(MAIN, "n-odd", "both")), selected.toList())
    }

    @Test
    fun `a pick the core refuses keeps the picker open under its banner, and 重試 switches and closes`() {
        busyPicks.set(1)
        val vm = viewModel(Honk())
        var pull: RvPullState? = null
        compose.setContent {
            RvTheme(rvSystemScheme(false), false) {
                NodesPull(vm, RvPullMotion.Sheet, rememberRvPullState(open = true).also { pull = it }, onDetail = {}, onAdd = {}, onAddNode = {}) {}
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
        assertEquals(listOf(Triple(MAIN, "n-odd", "both"), Triple(MAIN, "n-odd", "both")), selected.toList())
        assertEquals(null, vm.ui.value.failure)
    }

    @Test
    fun `the main node's page shows a failed test and a failed pick`() {
        testsFail = true
        busyPicks.set(1)
        val vm = viewModel(Honk())
        compose.setContent { RvTheme(rvSystemScheme(false), false) { MainNodeRoute(vm, onBack = {}, onSearch = {}, onAdd = {}, onAddNode = {}) } }
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
        val vm = viewModel(Honk(SAMPLE + N("n-mine", "Home 01", provider = null, protocol = null)))
        compose.setContent { RvTheme(rvSystemScheme(false), false) { NodeSearchRoute(vm, onClose = {}, onDetail = {}, onAdd = {}, onAddNode = {}) } }
        compose.waitForIdle()
        compose.onNodeWithText("In configuration").assertExists()
        compose.onNodeWithText("Home 01").assertExists()
        // Its details are not read yet (no protocol): its line is the place alone, not 「 / Other」.
        compose.onAllNodesWithText("Other", useUnmergedTree = true).onFirst().assertExists()
        compose.onAllNodesWithText(" / Other", useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithText("All subscriptions").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText("In configuration").onLast().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Home 01").assertExists()
        compose.onNodeWithText("Tokyo 01").assertDoesNotExist()
    }

    @Test
    fun `節點's 訂閱 chip shows one subscription's nodes and its 排序 chip orders them`() {
        val nodes = listOf(N("n-jp", "Tokyo 01", "s-1", 20), N("n-os", "Osaka 01", "s-1", 30), N("n-sg", "Singapore 01", "s-2", 10))
        val vm = viewModel(Honk(nodes, listOf(P("s-1", "Harbor", 2), P("s-2", "Bay", 1))))
        compose.setContent { RvTheme(rvSystemScheme(false), false) { NodesRoute(vm, onBack = {}, onOpen = {}, onAllNodes = {}, onAdd = {}, onAddNode = {}) } }
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
        val vm = viewModel(Honk())
        compose.setContent { RvTheme(rvSystemScheme(false), false) { NodesPull(vm, RvPullMotion.Sheet, rememberRvPullState(open = true), onDetail = {}, onAdd = {}, onAddNode = {}) {} } }
        compose.waitForIdle()
        compose.onNodeWithText("By latency").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Name").performClick()
        compose.waitForIdle()
        // 🍎 is drawn as Apple, so it sorts as Apple; 🦄 is no mark of ours and stays in the name.
        assertEquals(listOf("Apple", "Tokyo 01", "🦄 Osaka 02"), rowNames(setOf("Apple", "Tokyo 01", "🦄 Osaka 02")))
    }

    /** The node's page probes that node alone, as its row's menu does. */
    @Test
    fun `the node's page 測試延遲 tests that node, not every group`() {
        val vm = viewModel(Honk())
        compose.setContent { RvTheme(rvSystemScheme(false), false) { NodeRoute(vm, "n-jp", onBack = {}) } }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Test latency").performClick()
        compose.waitUntil(5_000) { tested.isNotEmpty() }
        assertEquals(listOf(NODE_PROBE), tested.toList())
    }

    /** The node rows' names, top to bottom. */
    private fun rowNames(names: Set<String> = setOf("Tokyo 01", "Osaka 01")): List<String> =
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true).fetchSemanticsNodes()
            .sortedBy { it.positionInRoot.y }
            .mapNotNull { node -> node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }?.takeIf { it in names } }
            .distinct()

    @Test
    fun `with the networks apart the picker checks both nodes and 套用到 sends a pick to one network`() {
        val vm = viewModel(Honk(udp = "n-odd"))
        compose.setContent { RvTheme(rvSystemScheme(false), false) { MainNodeRoute(vm, onBack = {}, onSearch = {}, onAdd = {}, onAddNode = {}) } }
        compose.waitForIdle()
        assertEquals(setOf("n-jp", "n-odd"), vm.ui.value.state.current)
        compose.onNodeWithText("Apply to").assertExists()
        compose.onNodeWithText("UDP").performClick()
        compose.onNodeWithText("Apple").performClick()
        compose.waitUntil(5_000) { selected.isNotEmpty() }
        assertEquals(listOf(Triple(MAIN, "n-apple", "udp")), selected.toList())
    }

    @Test
    fun `a node's page keeps the raw name`() {
        val vm = viewModel(Honk())
        var back = false
        compose.setContent { RvTheme(rvSystemScheme(false), false) { NodeRoute(vm, "n-jp", onBack = { back = true }) } }
        compose.waitForIdle()
        compose.onAllNodesWithText("🇯🇵 Tokyo 01", useUnmergedTree = true).onFirst().assertExists()
        compose.onAllNodesWithText("Tokyo 01").assertCountEquals(0)
        assertFalse(back)
    }

    @Test
    fun `with no main group the pages show no main node and the node's page cannot make one main`() {
        // A hand-edited profile: one group of the user's own, and a fallback that goes direct, so there is no main group.
        val vm = viewModel(Honk(listOf(N("n-jp", "Tokyo 01")), listOf(P("s-1", "Harbor", 1)), group = "g-Mine", name = "Mine", fallback = "direct"))
        var node by mutableStateOf(false)
        compose.setContent {
            RvTheme(rvSystemScheme(false), false) {
                if (node) NodeRoute(vm, "n-jp", onBack = {}) else NodesRoute(vm, onBack = {}, onOpen = {}, onAllNodes = {}, onAdd = {}, onAddNode = {})
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
        val vm = viewModel(Honk(udp = "n-odd"))
        compose.setContent { RvTheme(rvSystemScheme(false), false) { NodesRoute(vm, onBack = {}, onOpen = {}, onAllNodes = {}, onAdd = {}, onAddNode = {}) } }
        compose.waitForIdle()
        val inUse = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "In use")
        val checked = compose.onAllNodes(inUse).fetchSemanticsNodes().map { it.config[SemanticsProperties.Text].joinToString("") { t -> t.text } }
        assertEquals(2, checked.size)
        assertTrue(checked.toString(), checked.any { "Tokyo 01" in it } && checked.any { "Osaka 02" in it })
    }

    @Test
    fun `a failed read shows on 節點 and the node's page, and 重試 reads again`() {
        val vm = viewModel(Honk())
        var node by mutableStateOf(false)
        compose.setContent {
            RvTheme(rvSystemScheme(false), false) {
                if (node) NodeRoute(vm, "n-jp", onBack = {}) else NodesRoute(vm, onBack = {}, onOpen = {}, onAllNodes = {}, onAdd = {}, onAddNode = {})
            }
        }
        compose.waitForIdle()
        // A pick reads again; that read fails and keeps the nodes read before it.
        readsFail = true
        vm.pick("n-odd")
        compose.waitUntil(5_000) { vm.ui.value.failure != null }
        compose.onNodeWithText("Could not read the nodes").assertExists()
        node = true
        compose.waitForIdle()
        compose.onNodeWithText("Could not read the nodes").assertExists()
        readsFail = false
        compose.onNodeWithText("Try again").performClick()
        compose.waitUntil(5_000) { vm.ui.value.failure == null }
        compose.waitForIdle()
        compose.onNodeWithText("Could not read the nodes").assertDoesNotExist()
    }

    @Test
    fun `two subscriptions of one name stay apart in the 訂閱 menu, and one that gave no node is not offered`() {
        val nodes = listOf(N("n-jp", "Tokyo 01", "s-1", 20), N("n-os", "Osaka 01", "s-2", 30))
        val vm = viewModel(Honk(nodes, listOf(P("s-1", "Harbor", 1), P("s-2", "Harbor", 1), P("s-3", "Lantern", 0))))
        compose.setContent { RvTheme(rvSystemScheme(false), false) { NodesRoute(vm, onBack = {}, onOpen = {}, onAllNodes = {}, onAdd = {}, onAddNode = {}) } }
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
        val vm = viewModel(Honk(SAMPLE + N("n-mine", "Home 01", provider = null, protocol = null)))
        compose.setContent { RvTheme(rvSystemScheme(false), false) { NodeRoute(vm, "n-mine", onBack = {}) } }
        compose.waitForIdle()
        compose.onNodeWithText("In configuration", useUnmergedTree = true).assertExists()
    }

    /**
     * The app gives the rows' menus their actions (they were the sample's alone): 設為主節點, 測試延遲, 更新; honk keeps no
     * share link, so 複製分享連結 is there but takes no press.
     */
    @Test
    fun `the rows' menus run the app's own actions`() {
        val vm = viewModel(Honk())
        var pull by mutableStateOf(false)
        compose.setContent {
            RvTheme(rvSystemScheme(false), false) {
                if (pull) {
                    NodesPull(vm, RvPullMotion.Sheet, rememberRvPullState(open = true), onDetail = {}, onAdd = {}, onAddNode = {}) {}
                } else {
                    NodesRoute(vm, onBack = {}, onOpen = {}, onAllNodes = {}, onAdd = {}, onAddNode = {})
                }
            }
        }
        compose.waitForIdle()
        compose.onAllNodesWithText("🦄 Osaka 02").onFirst().performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithText("Set as main node").performClick()
        compose.waitUntil(5_000) { selected.isNotEmpty() }
        assertEquals(listOf(Triple(MAIN, "n-odd", "both")), selected.toList())
        compose.waitForIdle()
        compose.onAllNodesWithText("Tokyo 01").onFirst().performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithText("Test latency").performClick()
        compose.waitUntil(5_000) { tested.isNotEmpty() }
        assertEquals(listOf(NODE_PROBE), tested.toList())
        compose.waitForIdle()
        compose.onAllNodesWithText("Tokyo 01").onFirst().performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithText("Copy share link").assertExists().assertHasNoClickAction()
        // The picker's 訂閱 row: 更新 updates that subscription.
        pull = true
        compose.waitForIdle()
        compose.onAllNodesWithText("Harbor").onFirst().performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithText("Update").performClick()
        compose.waitUntil(5_000) { updated.isNotEmpty() }
        assertEquals(listOf("s-1"), updated.toList())
    }

    @Test
    fun `the picker says when a search matches nothing and lists no subscription that gave no node`() {
        val vm = viewModel(Honk(providers = listOf(P("s-1", "Harbor", 3), P("s-2", "Lantern", 0))))
        compose.setContent { RvTheme(rvSystemScheme(false), false) { NodeSearchRoute(vm, onClose = {}, onDetail = {}, onAdd = {}, onAddNode = {}) } }
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
        val vm = viewModel(Honk(nodes = emptyList()))
        var added = false
        var nodeAdded = false
        compose.setContent {
            RvTheme(rvSystemScheme(false), false) {
                NodesPull(vm, RvPullMotion.Sheet, rememberRvPullState(open = true), onDetail = {}, onAdd = { added = true }, onAddNode = { nodeAdded = true }) {}
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("All subscriptions").assertDoesNotExist()
        compose.onNodeWithText("Add node").performClick()
        assertTrue(nodeAdded)
        assertFalse(added)
        compose.onNodeWithText("Add subscription").performClick()
        assertTrue(added)
    }

    /**
     * Before the catalog is first read (a cold start, a restore after process death), search, the main node's page and the
     * pull-down picker show the list's skeleton, not the empty state and its 新增訂閱; the nodes come with the catalog.
     */
    @Test
    fun `before the first read the pickers show no empty state`() {
        val io = Held()
        val vm = viewModel(Honk(), io)
        var page by mutableIntStateOf(0)
        compose.setContent {
            RvTheme(rvSystemScheme(false), false) {
                when (page) {
                    0 -> NodeSearchRoute(vm, onClose = {}, onDetail = {}, onAdd = {}, onAddNode = {})
                    1 -> MainNodeRoute(vm, onBack = {}, onSearch = {}, onAdd = {}, onAddNode = {})
                    else -> NodesPull(vm, RvPullMotion.Sheet, rememberRvPullState(open = true), onDetail = {}, onAdd = {}, onAddNode = {}) {}
                }
            }
        }
        repeat(3) {
            page = it
            compose.waitForIdle()
            compose.onNodeWithText("Add subscription").assertDoesNotExist()
        }
        io.release()
        compose.waitForIdle()
        compose.onAllNodesWithText("Tokyo 01", substring = true).onFirst().assertExists()
    }

    /** 規則's 主節點 page carries every node picker's 訂閱 and 排序 chips (ruling q-p3c-mainchips), not the range row. */
    @Test
    fun `the main node's page filters by subscription with the pickers' chips`() {
        val nodes = listOf(N("n-jp", "Tokyo 01", "s-1", 20), N("n-sg", "Singapore 01", "s-2", 10))
        val vm = viewModel(Honk(nodes, listOf(P("s-1", "Harbor", 1), P("s-2", "Bay", 1))))
        compose.setContent { RvTheme(rvSystemScheme(false), false) { MainNodeRoute(vm, onBack = {}, onSearch = {}, onAdd = {}, onAddNode = {}) } }
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
        const val MAIN = "g-Proxy"
        const val NOW = 1_764_000_000_000L
        const val NODE_PROBE = """{"type":"node","node_id":"n-jp"}"""
        const val ERROR = """{"error":{"code":"conflict","message":"The request conflicts with the current state."},"request_id":"request-1"}"""
        val SAMPLE = listOf(N("n-jp", "🇯🇵 Tokyo 01"), N("n-odd", "🦄 Osaka 02"), N("n-apple", "🍎"))

        fun ok(body: String) = NativeResponse(200, body)

        fun node(n: N): String {
            val health = n.latency?.let { """{"transport":"tcp","state":"healthy","latency_ms":$it,"observed_at":"2026-11-22T10:00:00Z","error":null}""" }.orEmpty()
            val sub = n.provider?.let { "\"$it\"" } ?: "null"
            val protocol = n.protocol?.let { "\"$it\"" } ?: "null"
            return """{"id":"${n.id}","name":"${n.name}","protocol":$protocol,"stream_transport":null,"subscription_tag":$sub,"provider_id":$sub,""" +
                """"group_ids":[],"health":[$health]}"""
        }

        fun provider(p: P) = """{"id":"${p.id}","name":"${p.name}","kind":"subscription","url_redacted":"https://sub.example.net/${p.id}",""" +
            """"node_count":${p.nodes},"updated_at":null,"expires_at":null,"traffic":null,"status":"ok","last_error":null}"""

        fun accepted(id: String) = """{"operation_id":"$id","kind":"probe","status":"queued","href":"/api/v1/operations/$id"}"""

        fun succeeded(result: String) = """{"operation_id":"op","kind":"probe","status":"succeeded","result":$result,"error":null}"""
    }
}
