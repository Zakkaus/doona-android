package dev.doona.android.feature.nodes

import dev.doona.android.designsystem.rv.RvPlace
import dev.doona.android.engine.Engine
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.Group
import dev.doona.android.engine.GroupKind
import dev.doona.android.engine.LatencyTest
import dev.doona.android.engine.Member
import dev.doona.android.engine.Network
import dev.doona.android.engine.NodeInfo
import dev.doona.android.engine.Subscription
import dev.doona.android.engine.fake.FakeEngine
import dev.doona.android.engine.fake.FakeRuleMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class NodesViewModelTest {
    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** An engine over [base] whose details, select and test answer as set, recording what they were asked. */
    private class Probe(val base: FakeEngine, var infos: Map<String, NodeInfo>? = null) : Engine by base {
        var result: Result<Unit> = Result.success(Unit)
        val selected = mutableListOf<Triple<String, String?, Network?>>()
        val tested = mutableListOf<String?>()
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun nodes(ids: List<String>?): Result<List<NodeInfo>> =
            infos?.let { all -> Result.success(ids.orEmpty().mapNotNull(all::get)) } ?: base.nodes(ids)

        override suspend fun select(groupId: String, memberId: String?, network: Network?): Result<Unit> {
            selected += Triple(groupId, memberId, network)
            return if (result.isSuccess) base.select(groupId, memberId, network) else result
        }

        override suspend fun testLatency(groupId: String?): Result<Unit> {
            tested += groupId
            gate?.await()
            return result
        }
    }

    private class Counting(val inner: NodePlaces = CoreNodePlaces) : NodePlaces {
        val calls = AtomicInteger()

        override fun of(name: String): NodePlace {
            calls.incrementAndGet()
            return inner.of(name)
        }
    }

    private fun TestScope.collected(engine: Engine, places: NodePlaces = CoreNodePlaces) =
        NodesViewModel(engine, UnconfinedTestDispatcher(testScheduler), places).also { vm ->
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.ui.collect {} }
        }

    private val sub = Subscription("s-1", "Harbor", "https://sub.example.net/token", nodes = 2, updatedAtEpochMs = 1L)

    private fun engine(members: List<Member>, selected: String? = members.first().id) =
        FakeEngine(groups = listOf(Group(MAIN, "Proxy", GroupKind.Selector, members, Group.Selection(selected, pinned = true))), subscriptions = listOf(sub))

    @Test
    fun mapsEveryNodeOnceWithItsPlaceItsProtocolAndTheMainGroupsNode() = runTest {
        val members = listOf(Member("n-hk", "🇭🇰 Hong Kong 02", subscriptionId = "s-1"), Member("n-static", "Tokyo NRT"), Member("n-hk", "🇭🇰 Hong Kong 02", subscriptionId = "s-1"))
        val info = mapOf("n-hk" to NodeInfo("n-hk", "vless", "hk2.example.net", 443, "gRPC", true, emptyList()))
        val ui = collected(Probe(engine(members), info)).ui.value
        assertTrue(ui.loaded)
        assertEquals(listOf("n-hk", "n-static"), ui.state.nodes.map { it.id })
        val hk = ui.state.node("n-hk")!!
        assertEquals(RvPlace.HK, hk.place)
        assertEquals("HK 02", hk.short)
        assertEquals("VLESS", hk.protocol)
        assertEquals("🇭🇰 Hong Kong 02", hk.name)
        assertEquals("Harbor", hk.subscription)
        // A static node belongs to no subscription, so no 訂閱 section lists it.
        assertEquals("", ui.state.node("n-static")!!.subscription)
        assertEquals("n-hk", ui.state.currentId)
        assertEquals(MAIN, ui.state.mainGroupId)
        assertEquals(listOf("Harbor"), ui.state.subscriptions.map { it.name })
        assertEquals("sub.example.net", ui.state.subscriptions.single().host)
    }

    @Test
    fun aPickSwitchesTheMainGroupOnBothNetworksOrOnTheOneChosen() = runTest {
        val probe = Probe(engine(listOf(Member("n-a", "A 01"), Member("n-b", "B 02"))))
        val vm = collected(probe)
        vm.pick("n-b")
        vm.pick("n-a", Network.Udp)
        assertEquals(listOf(Triple(MAIN, "n-b", null), Triple(MAIN, "n-a", Network.Udp)), probe.selected)
        assertEquals("n-b", vm.ui.value.state.currentId)
        assertTrue(vm.ui.value.state.split)
    }

    @Test
    fun aFailedPickStaysUntilAPickSucceedsAndATestDoesNotClearIt() = runTest {
        val probe = Probe(engine(listOf(Member("n-a", "A 01"), Member("n-b", "B 02"))))
        val vm = collected(probe)
        probe.result = Result.failure(EngineException(ErrorCode.Busy))
        vm.pick("n-b")
        assertEquals(NodesFailure(NodesAction.Select, ErrorCode.Busy), vm.ui.value.failure)
        assertEquals("n-a", vm.ui.value.state.currentId)
        probe.result = Result.success(Unit)
        vm.test()
        assertEquals(NodesAction.Select, vm.ui.value.failure?.action)
        vm.pick("n-b")
        assertNull(vm.ui.value.failure)
    }

    @Test
    fun aTestAsksForEveryGroupAndShowsAsRunningUntilItAnswers() = runTest {
        val probe = Probe(engine(listOf(Member("n-a", "A 01"))))
        val vm = collected(probe)
        probe.gate = CompletableDeferred()
        vm.test()
        vm.test()
        assertTrue(vm.ui.value.testing)
        assertEquals(listOf<String?>(null), probe.tested)
        probe.gate!!.complete(Unit)
        assertFalse(vm.ui.value.testing)
    }

    @Test
    fun noneOrOneProbeRecordGiveAnEmptyOrSingleHistoryWithTheirOwnTimes() = runTest {
        val members = listOf(Member("n-0", "Zero 01"), Member("n-1", "One 01"), Member("n-c", "Catalog 01", latencyMs = 42))
        val info = mapOf(
            "n-0" to NodeInfo("n-0", "trojan", "h", 443, "TCP", true, emptyList()),
            "n-1" to NodeInfo("n-1", "trojan", "h", 443, "TCP", true, listOf(LatencyTest(1_000L, 88))),
        )
        val state = collected(Probe(engine(members), info)).ui.value.state
        assertEquals(emptyList<Int?>(), state.node("n-0")!!.history)
        assertEquals(emptyList<Long>(), state.testedAt["n-0"])
        assertEquals(listOf<Int?>(88), state.node("n-1")!!.history)
        assertEquals(listOf(1_000L), state.testedAt["n-1"])
        // No details: the catalog's own last probe, never a made-up one, with no time to show.
        assertEquals(listOf<Int?>(42), state.node("n-c")!!.history)
        assertEquals(emptyList<Long>(), state.testedAt["n-c"])
    }

    @Test
    fun hundredsOfNodesArePlacedOncePerCatalogRevision() = runTest {
        val members = List(NODES) { Member("n-$it", "Hong Kong ${it + 1}") }
        val places = Counting()
        val probe = Probe(engine(members))
        val vm = collected(probe, places)
        assertEquals(NODES, vm.ui.value.state.nodes.size)
        assertEquals(NODES, places.calls.get())
        // A failure or a test changes what the pages show, not the catalog: nothing is placed again.
        probe.result = Result.failure(EngineException(ErrorCode.Busy))
        vm.pick("n-3")
        vm.test()
        assertEquals(NODES, places.calls.get())
        // A pick that lands is a new revision.
        probe.result = Result.success(Unit)
        vm.pick("n-3")
        assertEquals(2 * NODES, places.calls.get())
        assertEquals("n-3", vm.ui.value.state.currentId)
    }

    @Test
    fun theMainGroupsNodeIsFollowedThroughANestedGroup() = runTest {
        val inner = Group("g-Auto", "Auto", GroupKind.UrlTest, listOf(Member("n-x", "X 01")), Group.Selection("n-x", pinned = false))
        val main = Group(MAIN, "Proxy", GroupKind.Selector, listOf(Member("g-Auto", "Auto", isGroup = true), Member("n-y", "Y 01")), Group.Selection("g-Auto", pinned = true))
        val ui = collected(FakeEngine(groups = listOf(main, inner))).ui.value
        assertEquals("n-x", ui.state.currentId)
        assertEquals(listOf("n-y", "n-x"), ui.state.nodes.map { it.id })
    }

    private companion object {
        const val NODES = 300
        const val MAIN = FakeRuleMode.MAIN_GROUP_ID
    }
}
