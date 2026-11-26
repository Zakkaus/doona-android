package dev.doona.android.feature.policies

import dev.doona.android.engine.ErrorCode
import dev.doona.android.feature.nodes.ui.RvApplyTo
import dev.doona.android.vpn.NativeApi
import dev.doona.android.vpn.NativeResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GroupViewModelTest {
    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** `WhileSubscribed` only maps while someone collects, as the screen does; honk is read in the test. */
    private fun TestScope.collected(honk: Honk, groupId: String = SELECTOR) =
        GroupViewModel(honk, groupId, clock = { NOW }, io = UnconfinedTestDispatcher(testScheduler)).also { vm ->
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        }

    /**
     * honk with a selector and a Score group over three nodes of provider s1, keeping each network's member and whether a
     * pin chose it; [calls] records the selections, clears and probes, which fail with [status] when it is set.
     */
    private class Honk(udpId: String = "n1", groups: List<G>? = null) : NativeApi {
        class G(val id: String, val name: String, val kind: String, val members: List<String>, var tcp: String?, var udp: String?, var pinned: Boolean)

        val groups = (groups ?: listOf(G(SELECTOR, "Streaming", "selector", NODES, "n1", udpId, false), G(SCORE, "Score", "score", NODES, "n1", "n2", false)))
            .associateBy { it.id }
        val calls = mutableListOf<String>()
        var status: Int? = null
        var failNodes = false

        override fun call(method: String, path: String, body: String?, headers: Map<String, String>): NativeResponse {
            val id = path.removePrefix("/api/v1/groups/").substringBefore("/").substringBefore("?")
            return when {
                method == "GET" && path == "/api/v1/nodes" -> if (failNodes) NativeResponse(500, ERROR) else ok(NODE_LIST)

                method == "GET" && path == "/api/v1/providers" -> ok(PROVIDERS)

                method == "GET" && path == "/api/v1/groups/$id" -> groups[id]?.let { ok(json(it)) } ?: NativeResponse(404, ERROR)

                method == "PUT" -> change("select $id ${field(body, "member_id")} ${field(body, "network")}") {
                    val group = groups.getValue(id)
                    val member = field(body, "member_id")
                    if (field(body, "network") != "udp") group.tcp = member
                    if (field(body, "network") != "tcp") group.udp = member
                    group.pinned = group.kind != "selector"
                }

                method == "DELETE" -> change("clear $id ${path.substringAfter("network=")}") { groups.getValue(id).pinned = false }

                method == "POST" && path == "/api/v1/probes" -> change("test ${Json.parseToJsonElement(body!!).jsonObject.getValue("target").jsonObject.getValue("group_id").jsonPrimitive.content}") {}
                    .let { if (it.status == 200) NativeResponse(202, ACCEPTED) else it }

                method == "GET" && path == "/api/v1/operations/op-1" -> ok("""{"operation_id":"op-1","kind":"probe","status":"succeeded","result":{},"error":null}""")

                else -> NativeResponse(404, ERROR)
            }
        }

        private fun change(call: String, apply: () -> Unit): NativeResponse {
            calls += call
            status?.let { return NativeResponse(it, ERROR) }
            apply()
            return ok("{}")
        }

        private fun json(g: G): String {
            val members = g.members.joinToString(",") { m -> """{"id":"$m","name":"$m","kind":"${if (m in groups) "group" else "node"}"}""" }
            fun sel(member: String?) = member?.let { m ->
                val leaf = groups[m]?.tcp ?: m
                """{"member_id":"$m","resolved_leaf_node_id":"$leaf","source":"${if (g.pinned) "override" else "runtime"}"}"""
            } ?: "null"
            return """{"id":"${g.id}","name":"${g.name}","icon":null,"policy":{"kind":"${g.kind}","native":"${g.kind}"},"members":[$members],""" +
                """"runtime":{"selection":{"tcp":${sel(g.tcp)},"udp":${sel(g.udp)}},"health":[]},"capabilities":{"can_select":true,"can_override":true}}"""
        }

        private fun field(body: String?, name: String) = Json.parseToJsonElement(body!!).jsonObject.getValue(name).jsonPrimitive.content
    }

    @Test
    fun `shows the group, its subscription and each network's node`() = runTest {
        val state = collected(Honk(udpId = "n3")).uiState.value
        assertFalse(state.loading)
        assertEquals(listOf("n1", "n2", "n3"), state.group!!.members.map { it.id })
        assertEquals(listOf("s1"), state.subscriptions.map { it.id })
        val choice = state.choice!!
        assertEquals("n1" to "n3", choice.tcpId to choice.udpId)
        assertTrue(choice.apart)
        assertFalse(state.isScore)
        assertNull("honk reports no Score evidence", state.score)
        assertEquals(NOW, state.nowEpochMs)
    }

    @Test
    fun `a tap with the switch off selects the node on both networks`() = runTest {
        val honk = Honk()
        val vm = collected(honk)
        assertFalse(vm.uiState.value.choice!!.apart)
        vm.onTap("n2")
        assertEquals(listOf("select $SELECTOR n2 both"), honk.calls)
        assertEquals("n2" to "n2", vm.uiState.value.choice!!.let { it.tcpId to it.udpId })
    }

    @Test
    fun `a tap with the switch on selects for the network 套用到 names`() = runTest {
        val honk = Honk()
        val vm = collected(honk)
        vm.onApart(true)
        vm.onApply(RvApplyTo.Both)
        vm.onTap("n2")
        vm.onApply(RvApplyTo.Tcp)
        vm.onTap("n3")
        vm.onApply(RvApplyTo.Udp)
        vm.onTap("n1")
        assertEquals(listOf("select $SELECTOR n2 both", "select $SELECTOR n3 tcp", "select $SELECTOR n1 udp"), honk.calls)
        assertEquals("n3" to "n1", vm.uiState.value.choice!!.let { it.tcpId to it.udpId })
        assertTrue(vm.uiState.value.choice!!.apart)
    }

    @Test
    fun `a tap in a Score group chooses nothing`() = runTest {
        val honk = Honk()
        val vm = collected(honk, SCORE)
        assertTrue(vm.uiState.value.isScore)
        vm.onTap("n3")
        assertEquals(emptyList<String>(), honk.calls)
    }

    @Test
    fun `a failed first read shows its failure, and 重試 reads again`() = runTest {
        val honk = Honk().apply { failNodes = true }
        val vm = collected(honk)
        assertFalse("no endless skeleton", vm.uiState.value.loading)
        assertEquals(GroupFailure(GroupAction.Read, ErrorCode.Internal), vm.uiState.value.failure)
        honk.failNodes = false
        vm.retry()
        assertNotNull(vm.uiState.value.group)
        assertNull(vm.uiState.value.failure)
    }

    @Test
    fun `a failed read keeps the group shown and says so, and 重試 reads again`() = runTest {
        val honk = Honk()
        val vm = collected(honk)
        honk.failNodes = true
        vm.onTap("n2")
        assertNotNull(vm.uiState.value.group)
        assertEquals(GroupFailure(GroupAction.Read, ErrorCode.Internal), vm.uiState.value.failure)
        honk.failNodes = false
        vm.retry()
        assertNull(vm.uiState.value.failure)
        assertEquals("n2", vm.uiState.value.choice!!.tcpId)
    }

    @Test
    fun `turning a Score group's switch off pins TCP's node on both networks, and on hands them back to Score`() = runTest {
        val honk = Honk()
        val vm = collected(honk, SCORE)
        assertTrue(vm.uiState.value.choice!!.apart)
        vm.onApart(false)
        assertEquals(listOf("select $SCORE n1 both"), honk.calls)
        val off = vm.uiState.value.choice!!
        assertFalse("the tap visibly turns the switch off", off.apart)
        assertEquals("n1" to "n1", off.tcpId to off.udpId)
        vm.onApart(true)
        assertEquals(listOf("select $SCORE n1 both", "clear $SCORE both"), honk.calls)
        assertTrue(vm.uiState.value.choice!!.apart)
    }

    @Test
    fun `turning on the switch of a Score group with no pin calls nothing`() = runTest {
        val honk = Honk()
        val vm = collected(honk, SCORE)
        vm.onApart(true)
        assertEquals(emptyList<String>(), honk.calls)
    }

    @Test
    fun `turning the switch off sends UDP back to TCP's node`() = runTest {
        val honk = Honk(udpId = "n3")
        val vm = collected(honk)
        vm.onApart(false)
        assertEquals(listOf("select $SELECTOR n1 udp"), honk.calls)
        val choice = vm.uiState.value.choice!!
        assertEquals("n1" to "n1", choice.tcpId to choice.udpId)
        assertFalse(choice.apart)
    }

    @Test
    fun `turning the switch off with one node on both networks calls nothing`() = runTest {
        val honk = Honk()
        val vm = collected(honk)
        vm.onApart(true)
        vm.onApart(false)
        assertEquals(emptyList<String>(), honk.calls)
    }

    @Test
    fun `testing probes this group, and the page knows while it runs`() = runTest {
        val honk = Honk()
        val vm = collected(honk)
        vm.onTest()
        assertTrue(vm.uiState.value.testing)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.testing)
        assertEquals(listOf("test $SELECTOR"), honk.calls)
    }

    @Test
    fun `a failure shows until the next action succeeds`() = runTest {
        val honk = Honk()
        val vm = collected(honk)
        honk.status = 404
        vm.onTap("n2")
        assertEquals(GroupFailure(GroupAction.Select, ErrorCode.NotFound), vm.uiState.value.failure)
        assertEquals("n1", vm.uiState.value.choice!!.tcpId)
        honk.status = null
        vm.onTest()
        advanceUntilIdle()
        assertNull(vm.uiState.value.failure)
    }

    @Test
    fun `retry runs the failed call again, as it was made`() = runTest {
        val honk = Honk("n2")
        val vm = collected(honk)
        vm.onApply(RvApplyTo.Udp)
        honk.status = 409
        vm.onTap("n3")
        honk.status = null
        vm.retry()
        assertEquals(listOf("select $SELECTOR n3 udp", "select $SELECTOR n3 udp"), honk.calls)
        assertNull(vm.uiState.value.failure)
        assertEquals("n3", vm.uiState.value.choice!!.udpId)
    }

    @Test
    fun `retry with nothing failed calls nothing`() = runTest {
        val honk = Honk()
        collected(honk).retry()
        assertTrue(honk.calls.isEmpty())
    }

    @Test
    fun `a group honk does not have has no page once read`() = runTest {
        val state = collected(Honk(), "g-Gone").uiState.value
        assertFalse(state.loading)
        assertNull(state.group)
        assertNull(state.choice)
    }

    @Test
    fun `a group among the members is listed as one, and a tap chooses it`() = runTest {
        val fast = Honk.G("g-快", "快", "urltest", listOf("n1", "n2"), "n2", "n2", false)
        val exit = Honk.G("g-出口", "出口", "selector", listOf("g-快", "n1"), "n1", "n1", false)
        val honk = Honk(groups = listOf(exit, fast))
        val vm = collected(honk, "g-出口")
        val group = vm.uiState.value.group!!
        assertEquals(listOf("n1"), group.members.map { it.id })
        assertEquals(listOf("g-快"), group.groups.map { it.id })
        assertEquals("n2", group.groups.single().node?.id)
        vm.onTap("g-快")
        assertEquals(listOf("select g-出口 g-快 both"), honk.calls)
        assertEquals("g-快", vm.uiState.value.choice!!.tcpId)
        assertEquals("n2", vm.uiState.value.group!!.selected?.id)
    }

    @Test
    fun `a group with no member still has a page`() = runTest {
        val honk = Honk(groups = listOf(Honk.G("g-空", "空", "selector", emptyList(), null, null, false)))
        val state = collected(honk, "g-空").uiState.value
        assertFalse(state.loading)
        assertEquals("g-空", state.group?.id)
        assertNull(state.group?.selected)
    }

    @Test
    fun `the page loads until its first read ends`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val vm = GroupViewModel(Honk(), SELECTOR, clock = { NOW }, io = io)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        assertTrue(vm.uiState.value.loading)
        runCurrent()
        assertFalse(vm.uiState.value.loading)
        assertEquals(SELECTOR, vm.uiState.value.group?.id)
    }

    private companion object {
        const val SELECTOR = "g-Streaming"
        const val SCORE = "g-Score"
        const val NOW = 1_764_000_000_000L
        val NODES = listOf("n1", "n2", "n3")
        const val ERROR = """{"error":{"code":"conflict","message":"The request conflicts with the current state."},"request_id":"request-1"}"""
        const val ACCEPTED = """{"operation_id":"op-1","kind":"probe","status":"queued","href":"/api/v1/operations/op-1"}"""
        const val PROVIDERS = """{"providers":[{"id":"s1","name":"Harbor","kind":"subscription","url_redacted":"https://sub.example/s1",""" +
            """"node_count":3,"updated_at":null,"expires_at":null,"traffic":null,"status":"ok","last_error":null}],"next_cursor":null}"""
        val NODE_LIST = NODES.joinToString(",", """{"nodes":[""", """],"next_cursor":null}""") {
            """{"id":"$it","name":"Node $it","protocol":"vless","subscription_tag":"s1","provider_id":"s1","group_ids":[],""" +
                """"health":[{"transport":"tcp","state":"healthy","latency_ms":40,"observed_at":"2026-11-22T10:00:00Z","error":null}]}"""
        }

        fun ok(body: String) = NativeResponse(200, body)
    }
}
