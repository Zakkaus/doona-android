package dev.doona.android.feature.policies

import dev.doona.android.engine.Engine
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.Group
import dev.doona.android.engine.GroupKind
import dev.doona.android.engine.Member
import dev.doona.android.engine.Network
import dev.doona.android.engine.NodeInfo
import dev.doona.android.engine.ScoreCounters
import dev.doona.android.engine.ScoreCoverage
import dev.doona.android.engine.ScoreGroup
import dev.doona.android.engine.ScoreNetwork
import dev.doona.android.engine.ScoreState
import dev.doona.android.engine.fake.FakeEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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

    /** `WhileSubscribed` only follows the engine while someone collects, as the screen does; reads run in the test. */
    private fun TestScope.collected(engine: Engine, groupId: String = SELECTOR) =
        GroupViewModel(engine, groupId, clock = { NOW }, reads = UnconfinedTestDispatcher(testScheduler)).also { vm ->
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        }

    /** A selector and a Score group over three nodes, recording the selects and probes and answering them with [result]. */
    private class Recording(udpId: String = "n1", private val base: FakeEngine = FakeEngine(groups(udpId))) : Engine by base {
        var result: Result<Unit> = Result.success(Unit)
        val calls = mutableListOf<String>()
        var failNodes = false
        var score: Result<ScoreState> = Result.success(ScoreState(0, listOf(evidence(2))))
        var scoreReads = 0

        override suspend fun scoreState(): Result<ScoreState> {
            scoreReads++
            return score
        }

        override suspend fun nodes(ids: List<String>?): Result<List<NodeInfo>> =
            if (failNodes) Result.failure(IllegalStateException("nodes")) else base.nodes(ids)

        override suspend fun select(groupId: String, memberId: String?, network: Network?): Result<Unit> {
            calls += "select $groupId $memberId $network"
            return if (result.isSuccess) base.select(groupId, memberId, network) else result
        }

        override suspend fun testLatency(groupId: String?): Result<Unit> {
            calls += "test $groupId"
            return result
        }

        companion object {
            private val members = listOf("n1", "n2", "n3").map { Member(it, "Node $it", latencyMs = 40, subscriptionId = "s1") }

            fun groups(udpId: String) = listOf(
                Group(SELECTOR, "Streaming", GroupKind.Selector, members, Group.Selection("n1", true), Group.Selection(udpId, true)),
                Group(SCORE, "Score", GroupKind.Score, members, Group.Selection("n1", false), Group.Selection("n2", false)),
            )
        }
    }

    @Test
    fun `shows the group, its subscription and each network's node`() = runTest {
        val state = collected(Recording(udpId = "n3")).uiState.value
        assertFalse(state.loading)
        assertEquals(listOf("n1", "n2", "n3"), state.group!!.members.map { it.id })
        assertEquals(listOf("s1"), state.subscriptions.map { it.id })
        val choice = state.choice!!
        assertEquals("n1" to "n3", choice.tcpId to choice.udpId)
        assertTrue(choice.apart)
        assertFalse(state.isScore)
        assertEquals(NOW, state.nowEpochMs)
    }

    @Test
    fun `a tap with the switch off selects the node on both networks`() = runTest {
        val engine = Recording()
        val vm = collected(engine)
        assertFalse(vm.uiState.value.choice!!.apart)
        vm.onTap("n2")
        assertEquals(listOf("select $SELECTOR n2 null"), engine.calls)
        assertEquals("n2" to "n2", vm.uiState.value.choice!!.let { it.tcpId to it.udpId })
    }

    @Test
    fun `a tap with the switch on selects for the network 套用到 names`() = runTest {
        val engine = Recording()
        val vm = collected(engine)
        vm.onApart(true)
        vm.onApply(RvApplyTo.Both)
        vm.onTap("n2")
        vm.onApply(RvApplyTo.Tcp)
        vm.onTap("n3")
        vm.onApply(RvApplyTo.Udp)
        vm.onTap("n1")
        val expected = listOf("select $SELECTOR n2 null", "select $SELECTOR n3 Tcp", "select $SELECTOR n1 Udp")
        assertEquals(expected, engine.calls)
        assertEquals("n3" to "n1", vm.uiState.value.choice!!.let { it.tcpId to it.udpId })
        assertTrue(vm.uiState.value.choice!!.apart)
    }

    @Test
    fun `a tap in a Score group chooses nothing`() = runTest {
        val engine = Recording()
        val vm = collected(engine, SCORE)
        assertTrue(vm.uiState.value.isScore)
        vm.onTap("n3")
        vm.onApart(false)
        assertEquals(emptyList<String>(), engine.calls)
    }

    @Test
    fun `a failed first read shows its failure, and 重試 reads again`() = runTest {
        val engine = Recording().apply { failNodes = true }
        val vm = collected(engine)
        assertFalse("no endless skeleton", vm.uiState.value.loading)
        assertEquals(GroupFailure(GroupAction.Read, ErrorCode.Internal), vm.uiState.value.failure)
        engine.failNodes = false
        vm.retry()
        assertNotNull(vm.uiState.value.group)
        assertNull(vm.uiState.value.failure)
    }

    @Test
    fun `a failed read keeps the group shown and says so, and 重試 reads again`() = runTest {
        val engine = Recording()
        val vm = collected(engine)
        engine.failNodes = true
        engine.select(SELECTOR, "n2", null)
        assertNotNull(vm.uiState.value.group)
        assertEquals(GroupFailure(GroupAction.Read, ErrorCode.Internal), vm.uiState.value.failure)
        engine.failNodes = false
        vm.retry()
        assertNull(vm.uiState.value.failure)
    }

    @Test
    fun `a Score group whose networks use two nodes keeps the switch on`() = runTest {
        val vm = collected(Recording(), SCORE)
        assertTrue(vm.uiState.value.choice!!.apart)
        vm.onApart(false)
        val choice = vm.uiState.value.choice!!
        assertTrue("one row would say n1 serves UDP too", choice.apart)
        assertEquals("n1" to "n2", choice.tcpId to choice.udpId)
    }

    @Test
    fun `turning the switch off sends UDP back to TCP's node`() = runTest {
        val engine = Recording(udpId = "n3")
        val vm = collected(engine)
        vm.onApart(false)
        assertEquals(listOf("select $SELECTOR n1 Udp"), engine.calls)
        val choice = vm.uiState.value.choice!!
        assertEquals("n1" to "n1", choice.tcpId to choice.udpId)
        assertFalse(choice.apart)
    }

    @Test
    fun `turning the switch off with one node on both networks calls nothing`() = runTest {
        val engine = Recording()
        val vm = collected(engine)
        vm.onApart(true)
        vm.onApart(false)
        assertEquals(emptyList<String>(), engine.calls)
    }

    @Test
    fun `testing probes this group`() = runTest {
        val engine = Recording()
        collected(engine).onTest()
        assertEquals(listOf("test $SELECTOR"), engine.calls)
    }

    @Test
    fun `a failure shows until the next action succeeds`() = runTest {
        val engine = Recording()
        val vm = collected(engine)
        engine.result = Result.failure(EngineException(ErrorCode.NotFound))
        vm.onTap("n2")
        assertEquals(GroupFailure(GroupAction.Select, ErrorCode.NotFound), vm.uiState.value.failure)
        assertEquals("n1", vm.uiState.value.choice!!.tcpId)
        engine.result = Result.success(Unit)
        vm.onTest()
        assertNull(vm.uiState.value.failure)
    }

    @Test
    fun `retry hands the failed call over again, as it was made`() = runTest {
        val engine = Recording("n2")
        val vm = collected(engine)
        vm.onApply(RvApplyTo.Udp)
        engine.result = Result.failure(EngineException(ErrorCode.Busy))
        vm.onTap("n3")
        engine.result = Result.success(Unit)
        vm.retry()
        assertEquals(listOf("select $SELECTOR n3 Udp", "select $SELECTOR n3 Udp"), engine.calls)
        assertNull(vm.uiState.value.failure)
        assertEquals("n3", vm.uiState.value.choice!!.udpId)
    }

    @Test
    fun `retry with nothing failed calls nothing`() = runTest {
        val engine = Recording()
        collected(engine).retry()
        assertTrue(engine.calls.isEmpty())
    }

    @Test
    fun `the page knows while its group is being tested`() = runTest {
        val base = FakeEngine(Recording.groups("n1"))
        val vm = collected(base)
        assertFalse(vm.uiState.value.testing)
        base.groups.value = base.groups.value.map { if (it.id == SELECTOR) it.copy(testing = true) else it }
        assertTrue(vm.uiState.value.testing)
    }

    @Test
    fun `an unknown group has no page once read`() = runTest {
        val state = collected(Recording(), "g-Gone").uiState.value
        assertFalse(state.loading)
        assertNull(state.group)
        assertNull(state.choice)
    }

    @Test
    fun `a group among the members is listed as one, and a tap chooses it`() = runTest {
        val nodes = listOf("n1", "n2").map { Member(it, "Node $it", latencyMs = 40, subscriptionId = "s1") }
        val fast = Group("g-快", "快", GroupKind.UrlTest, nodes, selectedId = "n2")
        val exit = Group("g-出口", "出口", GroupKind.Selector, listOf(Member("g-快", "快", isGroup = true), nodes[0]), selectedId = "n1")
        val engine = Recording(base = FakeEngine(listOf(exit, fast)))
        val vm = collected(engine, "g-出口")
        val group = vm.uiState.value.group!!
        assertEquals(listOf("n1"), group.members.map { it.id })
        assertEquals(listOf("g-快"), group.groups.map { it.id })
        assertEquals("n2", group.groups.single().node?.id)
        vm.onTap("g-快")
        assertEquals(listOf("select g-出口 g-快 null"), engine.calls)
        assertEquals("g-快", vm.uiState.value.choice!!.tcpId)
        assertEquals("n2", vm.uiState.value.group!!.selected?.id)
    }

    @Test
    fun `a group with no member still has a page`() = runTest {
        val engine = Recording(base = FakeEngine(listOf(Group("g-空", "空", GroupKind.Selector, emptyList(), selectedId = null))))
        val state = collected(engine, "g-空").uiState.value
        assertFalse(state.loading)
        assertEquals("g-空", state.group?.id)
        assertNull(state.group?.selected)
    }

    @Test
    fun `a Score group shows what honk compared, and only a Score group reads it`() = runTest {
        val engine = Recording()
        assertNull(collected(engine).uiState.value.score)
        assertEquals(0, engine.scoreReads)
        val state = collected(engine, SCORE).uiState.value
        assertEquals(evidence(2), state.score)
    }

    @Test
    fun `a Score group's evidence is read again while its page is shown`() = runTest {
        val engine = Recording()
        val vm = collected(engine, SCORE)
        engine.score = Result.success(ScoreState(0, listOf(evidence(3))))
        advanceTimeBy(GroupViewModel.SCORE_EVERY_MS + 1)
        assertEquals(evidence(3), vm.uiState.value.score)
    }

    @Test
    fun `a failed evidence read keeps what was compared and says so, and 重試 reads again`() = runTest {
        val engine = Recording()
        val vm = collected(engine, SCORE)
        engine.score = Result.failure(EngineException(ErrorCode.Busy))
        advanceTimeBy(GroupViewModel.SCORE_EVERY_MS + 1)
        assertEquals(evidence(2), vm.uiState.value.score)
        assertEquals(GroupFailure(GroupAction.Read, ErrorCode.Busy), vm.uiState.value.failure)
        engine.score = Result.success(ScoreState(0, listOf(evidence(4))))
        vm.retry()
        assertEquals(evidence(4), vm.uiState.value.score)
        assertNull(vm.uiState.value.failure)
    }

    @Test
    fun `a page restored before the engine loaded waits for the group's read, not only for ready`() = runTest {
        val base = FakeEngine(emptyList()).apply { ready.value = false }
        val reads = StandardTestDispatcher(testScheduler)
        val vm = GroupViewModel(base, SELECTOR, clock = { NOW }, reads = reads)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        runCurrent()
        base.groups.value = Recording.groups("n1")
        base.ready.value = true
        // The read of the catalog that has the group has not run yet: the one before found no such group.
        assertTrue("a page still loading, not one whose group is gone", vm.uiState.value.loading)
        runCurrent()
        assertFalse(vm.uiState.value.loading)
        assertEquals(SELECTOR, vm.uiState.value.group?.id)
    }

    private companion object {
        const val SELECTOR = "g-Streaming"
        const val SCORE = "g-Score"
        const val NOW = 1_764_000_000_000L

        /** What honk compared in [SCORE]: TCP on n1 against n3, UDP on n2, [evaluated] of three compared on each. */
        fun evidence(evaluated: Int) = ScoreGroup(SCORE, listOf("n1", "n2", "n3"), network("n1", evaluated), network("n2", evaluated))

        private fun network(id: String, evaluated: Int) =
            ScoreNetwork("Node $id", id, "observedUsable", "none", ScoreCoverage(3, evaluated, 0), emptyList(), ScoreCounters(0, 0, 0, 0))
    }
}
