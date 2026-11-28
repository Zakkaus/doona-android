package dev.doona.android.feature.nodes

import dev.doona.android.designsystem.rv.RvPlace
import dev.doona.android.engine.ErrorCode
import dev.doona.android.feature.model.CoreNodePlaces
import dev.doona.android.feature.model.NodePlace
import dev.doona.android.feature.model.NodePlaces
import dev.doona.android.honk.ActivityHonkFixtures.scoring
import dev.doona.android.honk.FakeHonk
import dev.doona.android.honk.HonkFixtures
import dev.doona.android.honk.SelectionNetwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/** The 節點 pages' state on honk's native API: the RV sample as honk serves it, and honk-shaped bodies for other states. */
@OptIn(ExperimentalCoroutinesApi::class)
class NodesViewModelTest {
    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class Counting(val inner: NodePlaces = CoreNodePlaces) : NodePlaces {
        val calls = AtomicInteger()

        override fun of(name: String): NodePlace {
            calls.incrementAndGet()
            return inner.of(name)
        }
    }

    private fun TestScope.collected(api: FakeHonk, places: NodePlaces = CoreNodePlaces) =
        NodesViewModel(api, UnconfinedTestDispatcher(testScheduler), places, clock = { NOW }).also { vm ->
            vm.load()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.ui.collect {} }
        }

    @Test
    fun mapsEveryNodeOnceWithItsPlaceItsProtocolAndTheMainGroupsNode() = runTest {
        val ui = collected(HonkFixtures.rvSample()).ui.value
        assertTrue(ui.loaded)
        assertEquals(48, ui.state.nodes.size)
        val hk = ui.state.node(HK2)!!
        assertEquals(RvPlace.HK, hk.place)
        assertEquals("VLESS", hk.protocol)
        assertEquals("gRPC", hk.transport)
        assertEquals("Hong Kong 02 IPLC", hk.name)
        assertEquals("Harbor Cloud", hk.subscription)
        assertEquals("s1", hk.subscriptionId)
        assertFalse("honk keeps no share link", hk.shareable)
        assertEquals(HK2, ui.state.currentId)
        assertEquals(setOf(HK2), ui.state.current)
        assertEquals("g-Proxy", ui.state.mainGroupId)
        assertFalse(ui.state.split)
        assertEquals(listOf("Harbor Cloud", "Lantern Net"), ui.state.subscriptions.map { it.name })
        assertNull("honk reports no Score evidence", ui.score)
        val marks = collected(HonkFixtures.rvSample().scoring()).ui.value.score
        assertEquals(RvScoreMarks(setOf(HK2), setOf("n-0001938e00000000")), marks)
    }

    @Test
    fun aNodesLastProbeIsItsOneTestWithItsTime() = runTest {
        val api = honk(nodes = listOf(node("n-1", "Tokyo 01", latency = 41), node("n-2", "Tokyo 02", state = "unavailable"), node("n-3", "Tokyo 03", state = null)))
        val state = collected(api).ui.value.state
        assertEquals(listOf(41), state.node("n-1")!!.history)
        assertEquals(listOf(null), state.node("n-2")!!.history)
        assertEquals(emptyList<Int?>(), state.node("n-3")!!.history)
        assertEquals(listOf(Instant.parse(PROBED).toEpochMilli()), state.testedAt["n-1"])
        assertEquals(1, state.testedAt["n-2"]!!.size)
        assertEquals(emptyList<Long>(), state.testedAt["n-3"])
    }

    @Test
    fun aNodeOfTheInlineProviderIsListedUnder組態內() = runTest {
        val state = collected(honk(nodes = listOf(node("n-1", "Home", provider = "inline")))).ui.value.state
        assertTrue(state.node("n-1")!!.inline)
        assertEquals("", state.node("n-1")!!.subscription)
    }

    @Test
    fun theMainGroupIsTheFallbacksAndItsNodeIsFollowedThroughANestedGroup() = runTest {
        val groups = listOf(
            group("g-HK", "HK", members = listOf("n-1" to "node")),
            group("g-Main", "Main", members = listOf("g-HK" to "group", "n-2" to "node"), tcp = sel("g-HK", leaf = "n-1"), udp = sel("n-2")),
        )
        val state = collected(honk(groups = groups, fallback = "Main")).ui.value.state
        assertEquals("g-Main", state.mainGroupId)
        assertEquals("n-1", state.currentId)
        assertEquals("n-2", state.udpId)
        assertTrue(state.split)
        assertEquals(setOf("n-1", "n-2"), state.current)
    }

    @Test
    fun oneNestedMemberCanResolveToDifferentNodesForTcpAndUdp() = runTest {
        val groups = listOf(
            group("g-HK", "HK", members = listOf("n-1" to "node", "n-2" to "node"), tcp = sel("n-1"), udp = sel("n-2")),
            group("g-Main", "Main", members = listOf("g-HK" to "group"), tcp = sel("g-HK", leaf = "n-1"), udp = sel("g-HK", leaf = "n-2")),
        )
        val state = collected(honk(groups = groups, fallback = "Main")).ui.value.state
        assertEquals(setOf("n-1", "n-2"), state.current)
        assertTrue(state.split)
    }

    @Test
    fun withADirectFallbackTheNodesStillListButNoneIsCurrentAndAPickDoesNothing() = runTest {
        val api = honk(fallback = "direct")
        val vm = collected(api)
        assertNull(vm.ui.value.state.mainGroupId)
        assertEquals("", vm.ui.value.state.currentId)
        assertEquals(2, vm.ui.value.state.nodes.size)
        vm.pick("n-1")
        assertTrue(api.calls.none { it.method == "PUT" })
    }

    @Test
    fun aPickSelectsInTheMainGroupOnBothNetworksOrTheOneChosenThenReadsAgain() = runTest {
        val api = HonkFixtures.rvSample().apply { on("PUT", "/api/v1/groups/g-Proxy/selection", 200, HonkFixtures.SELECTED) }
        val vm = collected(api)
        var picked = 0
        vm.pick(TOKYO, onPicked = { picked++ })
        vm.pick(TOKYO, SelectionNetwork.Udp)
        val bodies = api.calls.filter { it.method == "PUT" }.map { Json.parseToJsonElement(it.body!!).jsonObject }
        assertEquals(listOf("both", "udp"), bodies.map { it.getValue("network").jsonPrimitive.content })
        assertEquals(TOKYO, bodies.first().getValue("member_id").jsonPrimitive.content)
        assertEquals(1, picked)
        assertEquals("each pick reads the groups again", 3, api.calls.count { it.path == "/api/v1/groups" })
    }

    @Test
    fun aFailedPickStaysUntilRetrySucceedsAndThenCloses() = runTest {
        val api = HonkFixtures.rvSample().apply { on("PUT", "/api/v1/groups/g-Proxy/selection", 409, ERROR) }
        val vm = collected(api)
        var picked = 0
        vm.pick(TOKYO, onPicked = { picked++ })
        assertEquals(NodesFailure(NodesAction.Select, ErrorCode.Busy), vm.ui.value.failure)
        assertEquals(0, picked)
        api.on("PUT", "/api/v1/groups/g-Proxy/selection", 200, HonkFixtures.SELECTED)
        vm.retry()
        assertNull(vm.ui.value.failure)
        assertEquals(1, picked)
        assertEquals(NOW, vm.ui.value.nowEpochMs)
    }

    @Test
    fun aPickALaterOneSupersededDoesNotClose() = runTest {
        val api = HonkFixtures.rvSample().apply { on("PUT", "/api/v1/groups/g-Proxy/selection", 409, ERROR) }
        val vm = collected(api)
        var first = 0
        vm.pick(TOKYO, onPicked = { first++ })
        api.on("PUT", "/api/v1/groups/g-Proxy/selection", 200, HonkFixtures.SELECTED)
        var second = 0
        vm.pick(HK2, onPicked = { second++ })
        assertEquals(0, first)
        assertEquals(1, second)
    }

    @Test
    fun aTestProbesEveryGroupAndShowsAsRunningUntilHonkHasTheResults() = runTest {
        val api = probing(HonkFixtures.rvSample())
        val vm = collected(api)
        vm.test()
        assertEquals(emptySet<String>(), vm.ui.value.testing)
        advanceUntilIdle()
        assertNull(vm.ui.value.testing)
        val targets = api.calls.filter { it.path == "/api/v1/probes" }.map { Json.parseToJsonElement(it.body!!).jsonObject.getValue("target").jsonObject }
        assertEquals(8, targets.size)
        assertTrue(targets.all { it.getValue("type").jsonPrimitive.content == "group" })
        assertEquals("the results are read again", 2, api.calls.count { it.path == "/api/v1/nodes" })
    }

    @Test
    fun aOneNodeTestProbesThatNodeAndRetriesItself() = runTest {
        val api = HonkFixtures.rvSample().apply { on("POST", "/api/v1/probes", 503, ERROR) }
        val vm = collected(api)
        vm.testNode(TOKYO)
        advanceUntilIdle()
        assertEquals(NodesFailure(NodesAction.Test, ErrorCode.Busy), vm.ui.value.failure)
        probing(api)
        vm.retry()
        assertEquals(setOf(TOKYO), vm.ui.value.testing)
        advanceUntilIdle()
        assertNull(vm.ui.value.failure)
        val targets = api.calls.filter { it.path == "/api/v1/probes" }.map { Json.parseToJsonElement(it.body!!).jsonObject.getValue("target").toString() }
        assertEquals(List(2) { """{"type":"node","node_id":"$TOKYO"}""" }, targets)
    }

    @Test
    fun aFailedReadKeepsWhatWasShownAndRetryReadsAgain() = runTest {
        val api = HonkFixtures.rvSample()
        val vm = collected(api)
        api.on("GET", "/api/v1/nodes", 500, ERROR)
        api.on("PUT", "/api/v1/groups/g-Proxy/selection", 200, HonkFixtures.SELECTED)
        vm.pick(TOKYO)
        assertEquals(NodesFailure(NodesAction.Read, ErrorCode.Internal), vm.ui.value.failure)
        assertEquals(48, vm.ui.value.state.nodes.size)
        api.on("GET", "/api/v1/nodes", 200, HonkFixtures.body("nodes/rv-sample"))
        vm.retry()
        assertNull(vm.ui.value.failure)
    }

    @Test
    fun aFirstReadThatFailsShowsNoNodeUnderItsBanner() = runTest {
        val api = HonkFixtures.rvSample().apply { on("GET", "/api/v1/groups", 500, ERROR) }
        val ui = collected(api).ui.value
        assertFalse(ui.loaded)
        assertEquals(NodesState.Empty, ui.state)
        assertEquals(NodesAction.Read, ui.failure?.action)
    }

    @Test
    fun hundredsOfNodesArePlacedOncePerRead() = runTest {
        val places = Counting()
        collected(honk(nodes = List(300) { node("n-$it", "Tokyo $it") }), places)
        assertEquals(300, places.calls.get())
    }

    @Test
    fun aProvidersRefreshAndDeletionGoToHonkAndAFailureNamesIt() = runTest {
        val api = HonkFixtures.rvSample().apply {
            on("POST", "/api/v1/providers/s1/refresh", 409, ERROR)
            on("DELETE", "/api/v1/providers/s2", 200, """{"deleted":1}""")
        }
        val vm = collected(api)
        vm.update("s1")
        assertEquals(NodesFailure(NodesAction.Update, ErrorCode.Busy, "Harbor Cloud", providerId = "s1"), vm.ui.value.failure)
        assertEquals(emptySet<String>(), vm.ui.value.updating)
        vm.remove("s2")
        assertTrue(api.calls.any { it.method == "DELETE" && it.path == "/api/v1/providers/s2" })
        assertEquals("a deletion reads again", 2, api.calls.count { it.path == "/api/v1/providers" })
    }

    @Test
    fun aFailedDeletionRetriesTheConfirmedProviderAfterAnotherProviderIsUpdatedOrRemoved() = runTest {
        val a = "/api/v1/providers/s1"
        val b = "/api/v1/providers/s2"
        val removed = """{"deleted":1}"""
        val api = HonkFixtures.rvSample().apply {
            on("DELETE", a, 409, ERROR)
            on("DELETE", b, 200, removed)
            on("POST", "$b/refresh", 202, HonkFixtures.body("operations/provider-refresh-accepted"))
            on("GET", "/api/v1/operations/op-refresh", 200, HonkFixtures.body("operations/provider-refresh-s1"))
        }
        val vm = collected(api)
        vm.remove("s1")
        vm.update("s2")
        api.on("DELETE", a, 200, removed)
        vm.retry()
        assertEquals(listOf(a, a), api.calls.filter { it.method == "DELETE" }.map { it.path })
        assertNull(vm.ui.value.failure)

        api.on("DELETE", a, 409, ERROR)
        vm.remove("s1")
        vm.remove("s2")
        vm.retry()
        assertEquals(listOf(a, a, a, b, a), api.calls.filter { it.method == "DELETE" }.map { it.path })
    }

    private companion object {
        const val NOW = 1_764_000_000_000L
        const val HK2 = "n-0001938f00000000"
        const val TOKYO = "n-00019bab00000000"
        const val PROBED = "2026-11-22T10:00:00Z"
        const val ERROR = """{"error":{"code":"conflict","message":"The request conflicts with the current state."},"request_id":"request-1"}"""

        /** [api] with a probe honk accepts and finishes. */
        fun probing(api: FakeHonk) = api.apply {
            on("POST", "/api/v1/probes", 202, HonkFixtures.body("probes/queued"))
            on("GET", "/api/v1/operations/op-01HZX4K8W9", 200, HonkFixtures.body("operations/probe-complete"))
        }

        /** A running honk with [nodes], [groups] and a routing fallback to [fallback]; one provider, `s1`. */
        fun honk(
            nodes: List<String> = listOf(node("n-1", "Tokyo 01"), node("n-2", "Tokyo 02")),
            groups: List<String> = listOf(group("g-Proxy", "Proxy", members = listOf("n-1" to "node", "n-2" to "node"), tcp = sel("n-1"), udp = sel("n-1"))),
            fallback: String = "Proxy",
        ) = FakeHonk().apply {
            on("GET", "/api/v1/nodes", 200, """{"nodes":[${nodes.joinToString(",")}],"next_cursor":null}""")
            on("GET", "/api/v1/providers", 200, """{"providers":[${provider("s1", "Harbor")}],"next_cursor":null}""")
            on("GET", "/api/v1/groups", 200, groups.joinToString(",", "[", "]") { Json.parseToJsonElement(it).jsonObject.let { g -> """{"id":${g["id"]},"name":${g["name"]}}""" } })
            for (group in groups) on("GET", "/api/v1/groups/${Json.parseToJsonElement(group).jsonObject.getValue("id").jsonPrimitive.content}", 200, group)
            on("GET", "/api/v1/rules", 200, """{"generation_id":"1","rules":[],"fallback":{"outbound":"$fallback","source":null}}""")
        }

        /** A node with one TCP probe in [state] (none for null). */
        fun node(id: String, name: String, provider: String? = "s1", latency: Int? = 30, state: String? = "healthy"): String {
            val health = state?.let {
                val ms = latency.takeIf { state == "healthy" }
                """{"transport":"tcp","state":"$it","latency_ms":$ms,"observed_at":"$PROBED","error":null}"""
            }.orEmpty()
            val sub = provider?.let { "\"$it\"" } ?: "null"
            return """{"id":"$id","name":"$name","protocol":"vless","stream_transport":"grpc","subscription_tag":$sub,"provider_id":$sub,""" +
                """"group_ids":[],"health":[$health]}"""
        }

        fun provider(id: String, name: String) = """{"id":"$id","name":"$name","kind":"subscription","url_redacted":"https://sub.example/$id",""" +
            """"node_count":2,"updated_at":null,"expires_at":null,"traffic":null,"status":"ok","last_error":null}"""

        fun sel(member: String, leaf: String = member) = """{"member_id":"$member","resolved_leaf_node_id":"$leaf","source":"runtime"}"""

        /** A group of [members] (id to `node` or `group`) choosing [tcp] and [udp]. */
        fun group(id: String, name: String, kind: String = "selector", members: List<Pair<String, String>>, tcp: String? = null, udp: String? = null): String {
            val list = members.joinToString(",") { (member, of) -> """{"id":"$member","name":"$member","kind":"$of"}""" }
            return """{"id":"$id","name":"$name","icon":null,"policy":{"kind":"$kind","native":"$kind"},"members":[$list],""" +
                """"runtime":{"selection":{"tcp":${tcp ?: "null"},"udp":${udp ?: "null"}},"health":[]},""" +
                """"capabilities":{"can_select":true,"can_override":false}}"""
        }
    }
}
