package dev.doona.android.feature.nodes

import dev.doona.android.designsystem.rv.RvPlace
import dev.doona.android.engine.Catalog
import dev.doona.android.engine.Engine
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.Group
import dev.doona.android.engine.GroupKind
import dev.doona.android.engine.LatencyTest
import dev.doona.android.engine.Member
import dev.doona.android.engine.Network
import dev.doona.android.engine.NodeInfo
import dev.doona.android.engine.ScoreCounters
import dev.doona.android.engine.ScoreCoverage
import dev.doona.android.engine.ScoreGroup
import dev.doona.android.engine.ScoreNetwork
import dev.doona.android.engine.ScoreState
import dev.doona.android.engine.ShareLink
import dev.doona.android.engine.Subscription
import dev.doona.android.engine.SubscriptionFetch
import dev.doona.android.engine.SubscriptionFormat
import dev.doona.android.engine.fake.FakeEngine
import dev.doona.android.engine.fake.FakeRuleMode
import dev.doona.android.feature.model.CoreNodePlaces
import dev.doona.android.feature.model.NodePlace
import dev.doona.android.feature.model.NodePlaces
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    private class Probe(
        val base: FakeEngine,
        var infos: Map<String, NodeInfo>? = null,
        mainGroupId: String? = null,
        revisions: MutableStateFlow<Catalog?>? = null,
    ) : Engine by base {
        override val catalog: StateFlow<Catalog?> =
            revisions ?: mainGroupId?.let { id -> MutableStateFlow(base.catalog.value?.copy(mainGroupId = id)) } ?: base.catalog

        var result: Result<Unit> = Result.success(Unit)
        val selected = mutableListOf<Triple<String, String?, Network?>>()
        val tested = mutableListOf<String?>()
        var gate: CompletableDeferred<Unit>? = null
        var readFails = false
        val selectGates = mutableMapOf<String, CompletableDeferred<Unit>>()
        val links = mutableMapOf<String, String>()
        val updated = mutableListOf<String>()
        val removed = mutableListOf<String>()
        var subFails = false
        var score: Result<ScoreState>? = null

        override suspend fun scoreState(): Result<ScoreState> = score ?: base.scoreState()

        override suspend fun nodeShareLink(nodeId: String): Result<ShareLink> =
            links[nodeId]?.let { Result.success(ShareLink(it)) } ?: Result.failure(EngineException(ErrorCode.NotFound))

        override suspend fun updateSubscription(id: String): Result<Subscription> {
            updated += id
            return if (subFails) Result.failure(EngineException(ErrorCode.FetchFailed)) else base.updateSubscription(id)
        }

        override suspend fun removeSubscription(id: String): Result<Unit> {
            removed += id
            return if (subFails) Result.failure(EngineException(ErrorCode.Busy)) else base.removeSubscription(id)
        }

        override suspend fun nodes(ids: List<String>?): Result<List<NodeInfo>> = when {
            readFails -> Result.failure(EngineException(ErrorCode.Busy))
            else -> infos?.let { all -> Result.success(ids?.mapNotNull(all::get) ?: all.values.toList()) } ?: base.nodes(ids)
        }

        override suspend fun select(groupId: String, memberId: String?, network: Network?): Result<Unit> {
            selected += Triple(groupId, memberId, network)
            memberId?.let(selectGates::get)?.await()
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
        NodesViewModel(engine, UnconfinedTestDispatcher(testScheduler), places, clock = { NOW }).also { vm ->
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.ui.collect {} }
        }

    private val sub = Subscription("s-1", "Harbor", "https://sub.example.net/token", nodes = 2, lastFetch = SubscriptionFetch(1L, SubscriptionFormat.Clash))

    private fun engine(members: List<Member>, selected: String? = members.first().id) =
        FakeEngine(groups = listOf(Group(MAIN, "Proxy", GroupKind.Selector, members, Group.Selection(selected, pinned = true))), subscriptions = listOf(sub))

    @Test
    fun mapsEveryNodeOnceWithItsPlaceItsProtocolAndTheMainGroupsNode() = runTest {
        val members = listOf(Member("n-hk", "🇭🇰 Hong Kong 02", subscriptionId = "s-1"), Member("n-static", "Tokyo NRT"), Member("n-hk", "🇭🇰 Hong Kong 02", subscriptionId = "s-1"))
        val info = mapOf("n-hk" to NodeInfo("n-hk", null, "vless", "hk2.example.net", 443, "grpc", true, emptyList()))
        val ui = collected(Probe(engine(members), info)).ui.value
        assertTrue(ui.loaded)
        assertEquals(listOf("n-hk", "n-static"), ui.state.nodes.map { it.id })
        val hk = ui.state.node("n-hk")!!
        assertEquals(RvPlace.HK, hk.place)
        assertEquals("HK 02", hk.short)
        assertEquals("VLESS", hk.protocol)
        assertEquals("gRPC", hk.transport)
        assertEquals("🇭🇰 Hong Kong 02", hk.name)
        assertEquals("Harbor", hk.subscription)
        assertEquals("s-1", hk.subscriptionId)
        // A static node belongs to no subscription, so no 訂閱 section lists it.
        assertEquals("", ui.state.node("n-static")!!.subscriptionId)
        assertTrue(ui.state.node("n-static")!!.inline)
        assertEquals("n-hk", ui.state.currentId)
        assertEquals(MAIN, ui.state.mainGroupId)
        assertEquals(listOf("Harbor"), ui.state.subscriptions.map { it.name })
        assertEquals("sub.example.net", ui.state.subscriptions.single().host)
    }

    /** Only a share-link subscription's node has a link the core keeps (Engine.nodeShareLink); a legacy one, of no format, has none until refetched. */
    @Test
    fun aNodeIsShareableOnlyFromAShareLinkSubscription() {
        val subs = listOf(SubscriptionFormat.ShareLinks, SubscriptionFormat.Clash, null).mapIndexed { i, f -> sub.copy(id = "s-$i", lastFetch = f?.let { SubscriptionFetch(1L, it) }) }
        val members = subs.mapIndexed { i, it -> Member("n-$i", "Node 0$i", subscriptionId = it.id) } + Member("n-static", "Tokyo NRT")
        val catalog = FakeEngine(groups = listOf(Group(MAIN, "Proxy", GroupKind.Selector, members, Group.Selection("n-0", pinned = true))), subscriptions = subs).catalog.value!!
        val state = nodesState(catalog, emptyMap(), CoreNodePlaces, ungrouped = true)
        assertEquals(listOf(true, false, false, false), state.nodes.map { it.shareable })
    }

    /** A hand-edited profile (owner, 2026-11-26): a main group of the user's own name over a nested group, no Proxy group, a node in no group. */
    @Test
    fun aHandEditedProfileListsEveryNodeAndFollowsItsMainGroupThroughANestedOne() = runTest {
        val asia = Group("g-Asia", "Asia", GroupKind.UrlTest, listOf(Member("n-jp", "Tokyo 01", subscriptionId = "s-1"), Member("n-sg", "Singapore 01", subscriptionId = "s-1")), Group.Selection("n-sg", pinned = false))
        val mine = Group("g-Mine", "Mine", GroupKind.Selector, listOf(Member("n-us", "Dallas 01", subscriptionId = "s-1"), Member("g-Asia", "Asia", isGroup = true)), Group.Selection("g-Asia", pinned = true))
        val infos = listOf("n-jp", "n-lone", "n-sg", "n-us").associateWith { NodeInfo(it, null, "trojan", "$it.example.net", 443, "tcp", true, emptyList()) }
        val probe = Probe(FakeEngine(groups = listOf(mine, asia), subscriptions = listOf(sub)), infos, mainGroupId = "g-Mine")
        val vm = collected(probe)
        val state = vm.ui.value.state
        // Every group's nodes in the catalog's order, then the node no group lists, named by its server.
        assertEquals(listOf("n-us", "n-jp", "n-sg", "n-lone"), state.nodes.map { it.id })
        assertEquals("n-lone.example.net", state.node("n-lone")!!.name)
        assertEquals("", state.node("n-lone")!!.subscription)
        assertEquals("g-Mine", state.mainGroupId)
        assertEquals("n-sg", state.currentId)
        vm.pick("n-jp")
        assertEquals(listOf(Triple("g-Mine", "n-jp", null)), probe.selected)
    }

    @Test
    fun withNoMainGroupTheNodesStillListAndTestButNoneIsCurrentAndAPickDoesNothing() = runTest {
        val mine = Group("g-Mine", "Mine", GroupKind.Selector, listOf(Member("n-a", "A 01", subscriptionId = "s-1"), Member("n-b", "B 02", subscriptionId = "s-1")), Group.Selection("n-a", pinned = true))
        // The catalog names g-Proxy as its main group, which this profile does not have.
        val probe = Probe(FakeEngine(groups = listOf(mine), subscriptions = listOf(sub)))
        val vm = collected(probe)
        val ui = vm.ui.value
        assertEquals(listOf("n-a", "n-b"), ui.state.nodes.map { it.id })
        assertNull(ui.state.mainGroupId)
        assertEquals("", ui.state.currentId)
        assertFalse(ui.state.split)
        vm.pick("n-b")
        assertTrue(probe.selected.isEmpty())
        assertNull(vm.ui.value.failure)
        vm.test()
        assertEquals(listOf<String?>(null), probe.tested)
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
    fun retryRunsTheFailedPickOrTestAgainAndTheClockIsNow() = runTest {
        val probe = Probe(engine(listOf(Member("n-a", "A 01"), Member("n-b", "B 02"))))
        val vm = collected(probe)
        assertEquals(NOW, vm.ui.value.nowEpochMs)
        probe.result = Result.failure(EngineException(ErrorCode.Busy))
        vm.pick("n-b", Network.Tcp)
        probe.result = Result.success(Unit)
        vm.retry()
        assertEquals(listOf(Triple(MAIN, "n-b", Network.Tcp), Triple(MAIN, "n-b", Network.Tcp)), probe.selected)
        assertNull(vm.ui.value.failure)
        probe.result = Result.failure(EngineException(ErrorCode.Busy))
        vm.test()
        assertEquals(NodesAction.Test, vm.ui.value.failure?.action)
        probe.result = Result.success(Unit)
        vm.retry()
        assertEquals(listOf<String?>(null, null), probe.tested)
        assertNull(vm.ui.value.failure)
        // With nothing failed, a retry does nothing.
        vm.retry()
        assertEquals(2, probe.selected.size)
        assertEquals(2, probe.tested.size)
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
            "n-0" to NodeInfo("n-0", null, "trojan", "h", 443, "TCP", true, emptyList()),
            "n-1" to NodeInfo("n-1", null, "trojan", "h", 443, "TCP", true, listOf(LatencyTest(1_000L, 88))),
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
    fun withTheNetworksApartEachNetworksNodeIsCurrentThroughANestedGroup() = runTest {
        val members = listOf(Member("n-a", "A 01", subscriptionId = "s-1"), Member("n-b", "B 01", subscriptionId = "s-1"))
        val inner = Group("g-Inner", "Inner", GroupKind.Selector, members, Group.Selection("n-a", pinned = true), Group.Selection("n-b", pinned = true))
        val main = Group(MAIN, "Proxy", GroupKind.Selector, listOf(Member("g-Inner", "Inner", isGroup = true)), Group.Selection("g-Inner", pinned = true))
        val state = collected(FakeEngine(groups = listOf(main, inner), subscriptions = listOf(sub))).ui.value.state
        assertEquals("n-a", state.currentId)
        assertEquals("n-b", state.udpId)
        assertEquals(setOf("n-a", "n-b"), state.current)
    }

    @Test
    fun theMainGroupsNodeIsFollowedThroughANestedGroup() = runTest {
        val inner = Group("g-Auto", "Auto", GroupKind.UrlTest, listOf(Member("n-x", "X 01")), Group.Selection("n-x", pinned = false))
        val main = Group(MAIN, "Proxy", GroupKind.Selector, listOf(Member("g-Auto", "Auto", isGroup = true), Member("n-y", "Y 01")), Group.Selection("g-Auto", pinned = true))
        val ui = collected(FakeEngine(groups = listOf(main, inner))).ui.value
        assertEquals("n-x", ui.state.currentId)
        assertEquals(listOf("n-y", "n-x"), ui.state.nodes.map { it.id })
    }

    /** A failed read of the details shows with 重試; the nodes still list, with no details the core never sent. */
    @Test
    fun aFailedReadOfTheDetailsShowsAndRetryReadsThemAgain() = runTest {
        val info = mapOf("n-a" to NodeInfo("n-a", null, "vless", "a.example.net", 443, "tcp", true, emptyList()))
        val probe = Probe(engine(listOf(Member("n-a", "A 01", subscriptionId = "s-1"))), info).apply { readFails = true }
        val vm = collected(probe)
        assertEquals(NodesFailure(NodesAction.Read, ErrorCode.Busy), vm.ui.value.failure)
        val bare = vm.ui.value.state.node("n-a")!!
        assertFalse(bare.described)
        assertEquals("", bare.host)
        probe.readFails = false
        vm.retry()
        assertNull(vm.ui.value.failure)
        assertEquals("a.example.net", vm.ui.value.state.node("n-a")!!.host)
        assertTrue(vm.ui.value.state.node("n-a")!!.described)
    }

    /**
     * A subscription deleted while the read of the details fails: the new revision's rows are the nodes its groups list,
     * with the details last read, and the deleted subscription's node that no group listed is gone, not a stale row.
     */
    @Test
    fun aFailedReadNeverBringsBackANodeOfAnOlderRevision() = runTest {
        val listed = Member("n-a", "A 01", subscriptionId = "s-1")
        val base = engine(listOf(listed))
        val revisions = MutableStateFlow(base.catalog.value)
        val info = listOf("n-a", "n-lone").associateWith { NodeInfo(it, null, "vless", "$it.example.net", 443, "tcp", true, emptyList()) }
        val probe = Probe(base, info, revisions = revisions)
        val vm = collected(probe)
        assertEquals(listOf("n-a", "n-lone"), vm.ui.value.state.nodes.map { it.id })
        probe.readFails = true
        revisions.value = revisions.value!!.copy(subscriptions = emptyList())
        assertEquals(NodesFailure(NodesAction.Read, ErrorCode.Busy), vm.ui.value.failure)
        assertEquals(listOf("n-a"), vm.ui.value.state.nodes.map { it.id })
        assertEquals("n-a.example.net", vm.ui.value.state.node("n-a")!!.host)
        probe.readFails = false
        probe.infos = info - "n-lone"
        vm.retry()
        assertNull(vm.ui.value.failure)
        assertEquals(listOf("n-a"), vm.ui.value.state.nodes.map { it.id })
    }

    /** A failed read of Score's choices shows as the details' does, keeps the last marks, and 重試 reads them again. */
    @Test
    fun aFailedScoreReadShowsKeepsTheLastMarksAndRetries() = runTest {
        val members = listOf(Member("n-a", "A 01", subscriptionId = "s-1"), Member("n-b", "B 02", subscriptionId = "s-1"))
        val base = FakeEngine(groups = listOf(Group(MAIN, "Proxy", GroupKind.Score, members, Group.Selection("n-a", pinned = false))), subscriptions = listOf(sub))
        val revisions = MutableStateFlow(base.catalog.value)
        val probe = Probe(base, revisions = revisions).apply { score = Result.success(ScoreState(0, listOf(ScoreGroup(MAIN, listOf("n-a", "n-b"), chose("n-a"), chose("n-a"))))) }
        val vm = collected(probe)
        val marks = RvScoreMarks(setOf("n-a"), setOf("n-a"))
        assertEquals(marks, vm.ui.value.score)
        probe.score = Result.failure(EngineException(ErrorCode.Busy))
        revisions.value = revisions.value!!.copy(subscriptions = listOf(sub.copy(nodes = 3)))
        assertEquals(NodesFailure(NodesAction.Read, ErrorCode.Busy), vm.ui.value.failure)
        assertEquals(marks, vm.ui.value.score)
        probe.score = Result.success(ScoreState(0, listOf(ScoreGroup(MAIN, listOf("n-a", "n-b"), chose("n-b"), chose("n-a")))))
        vm.retry()
        assertNull(vm.ui.value.failure)
        assertEquals(RvScoreMarks(setOf("n-b"), setOf("n-a")), vm.ui.value.score)
    }

    /** Two quick picks: the first one's answer does not close the page while the second is still switching. */
    @Test
    fun aPickALaterOneSupersededDoesNotClose() = runTest {
        val probe = Probe(engine(listOf(Member("n-a", "A 01"), Member("n-b", "B 02"), Member("n-c", "C 03")), selected = "n-c"))
        val vm = collected(probe)
        val first = CompletableDeferred<Unit>().also { probe.selectGates["n-a"] = it }
        val second = CompletableDeferred<Unit>().also { probe.selectGates["n-b"] = it }
        val closed = mutableListOf<String>()
        vm.pick("n-a") { closed += "n-a" }
        vm.pick("n-b") { closed += "n-b" }
        first.complete(Unit)
        assertEquals(emptyList<String>(), closed)
        second.complete(Unit)
        assertEquals(listOf("n-b"), closed)
    }

    /** A probe that timed out (failed, no latency) is a test with no answer, 逾時, not a node never tested. */
    @Test
    fun aTimedOutProbeIsATestWithNoAnswer() = runTest {
        val ui = collected(engine(listOf(Member("n-a", "A 01", latencyMs = null, failed = true), Member("n-b", "B 02")))).ui.value
        assertEquals(listOf<Int?>(null), ui.state.node("n-a")!!.history)
        assertEquals(emptyList<Int?>(), ui.state.node("n-b")!!.history)
    }

    /**
     * A row menu's 測試延遲 runs one plan, on the main group when it lists the node and else on the first group that does
     * (each plan starts a new probe round, so a second would drop the first's results); a failure retries that test.
     */
    @Test
    fun aOneNodeTestRunsOnePlanAndRetriesItself() = runTest {
        val other = Group("g-Other", "Other", GroupKind.UrlTest, listOf(Member("n-a", "A 01"), Member("n-c", "C 03")), Group.Selection("n-a", pinned = false))
        val main = Group(MAIN, "Proxy", GroupKind.Selector, listOf(Member("n-a", "A 01"), Member("n-b", "B 02")), Group.Selection("n-a", pinned = true))
        val probe = Probe(FakeEngine(groups = listOf(other, main), subscriptions = listOf(sub)))
        val vm = collected(probe)
        vm.testNode("n-a")
        assertEquals(listOf<String?>(MAIN), probe.tested)
        probe.result = Result.failure(EngineException(ErrorCode.Internal))
        vm.testNode("n-c")
        assertEquals(listOf(MAIN, "g-Other"), probe.tested)
        assertEquals(NodesAction.Test, vm.ui.value.failure?.action)
        probe.result = Result.success(Unit)
        vm.retry()
        assertEquals(listOf(MAIN, "g-Other", "g-Other"), probe.tested)
        assertNull(vm.ui.value.failure)
    }

    /** 複製分享連結 copies the core's link; a node the core has none for fails with its banner, and the link is never made up. */
    @Test
    fun aShareLinkComesFromTheCoreAndANodeWithoutOneFails() = runTest {
        val probe = Probe(engine(listOf(Member("n-a", "A 01"), Member("n-b", "B 02"))))
        probe.links["n-a"] = "vless://id@a.example.net:443#A"
        val vm = collected(probe)
        val copied = mutableListOf<String>()
        vm.copyLink("n-a") { copied += it }
        assertEquals(listOf("vless://id@a.example.net:443#A"), copied)
        vm.copyLink("n-b") { copied += it }
        assertEquals(1, copied.size)
        assertEquals(NodesFailure(NodesAction.Share, ErrorCode.NotFound), vm.ui.value.failure)
    }

    /** A subscription's 更新 shows it updating until the core answers; a failed one names it; 刪除 deletes it. */
    @Test
    fun aSubscriptionsUpdateAndDeleteGoToTheCoreAndAFailureNamesIt() = runTest {
        val probe = Probe(engine(listOf(Member("n-a", "A 01", subscriptionId = "s-1")))).apply { subFails = true }
        val vm = collected(probe)
        vm.update("s-1")
        assertEquals(listOf("s-1"), probe.updated)
        assertEquals(emptySet<String>(), vm.ui.value.updating)
        assertEquals(NodesFailure(NodesAction.Update, ErrorCode.FetchFailed, "Harbor"), vm.ui.value.failure)
        probe.subFails = false
        vm.retry()
        assertEquals(listOf("s-1", "s-1"), probe.updated)
        assertNull(vm.ui.value.failure)
        vm.remove("s-1")
        assertEquals(listOf("s-1"), probe.removed)
    }

    @Test
    fun scoreMarksAreTheNodesTheScoreGroupsChosePerNetwork() {
        assertNull(rvScoreMarks(emptyList()))
        val marks = rvScoreMarks(listOf(ScoreGroup("g-s", emptyList(), chose("n-a"), chose("n-b")), ScoreGroup("g-t", emptyList(), chose(null), chose("n-a"))))
        assertEquals(RvScoreMarks(setOf("n-a"), setOf("n-b", "n-a")), marks)
    }

    private fun chose(id: String?) = ScoreNetwork(id, id, "", "", ScoreCoverage(0, 0, 0), emptyList(), ScoreCounters(0, 0, 0, 0))

    private companion object {
        const val NODES = 300
        const val MAIN = FakeRuleMode.MAIN_GROUP_ID
        const val NOW = 1_764_000_000_000L
    }
}
