package dev.doona.android.feature.activity

import dev.doona.android.engine.Catalog
import dev.doona.android.engine.Engine
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.Mode
import dev.doona.android.engine.NodeInfo
import dev.doona.android.engine.RuleBytes
import dev.doona.android.engine.RuleKind
import dev.doona.android.engine.RulePage
import dev.doona.android.engine.RuleRow
import dev.doona.android.engine.RuleTarget
import dev.doona.android.engine.Status
import dev.doona.android.engine.Usage
import dev.doona.android.engine.fake.FakeEngine
import dev.doona.android.engine.fake.FakeRuleMode
import dev.doona.android.engine.fake.rvSampleEngine
import dev.doona.android.feature.model.RvRouteMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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
import java.time.ZoneOffset

/** [ActivityViewModel.ui]: 活動's RV page model from the engine. */
@OptIn(ExperimentalCoroutinesApi::class)
class ActivityRvViewModelTest {
    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** Counts what 活動 must not do on its own (connect) and how often it reads usage and rules. */
    private class Recording(private val fake: Engine) : Engine by fake {
        var connects = 0
        var usageReads = 0
        var ruleReads = 0
        var nodeReads = 0
        var failMode: ErrorCode? = null
        var failTest: ErrorCode? = null

        /** When true, every later node read fails. */
        var failNodes = false

        override suspend fun nodes(ids: List<String>?): Result<List<NodeInfo>> {
            nodeReads++
            return if (failNodes) Result.failure(EngineException(ErrorCode.Internal)) else fake.nodes(ids)
        }

        override fun connect() {
            connects++
        }

        /** When set, what every later usage read returns in place of the fake's. */
        var nextUsage: Usage? = null

        override suspend fun usage(): Result<Usage> {
            usageReads++
            return nextUsage?.let { Result.success(it) } ?: fake.usage()
        }

        override suspend fun rules(offset: Int, limit: Int): Result<RulePage> {
            ruleReads++
            return fake.rules(offset, limit)
        }

        override suspend fun setMode(mode: Mode): Result<Unit> = failMode?.let { Result.failure(EngineException(it)) } ?: fake.setMode(mode)

        override suspend fun testLatency(groupId: String?): Result<Unit> = failTest?.let { Result.failure(EngineException(it)) } ?: fake.testLatency(groupId)
    }

    private fun TestScope.model(
        engine: Engine,
        crash: Boolean = false,
        online: Flow<Boolean> = flowOf(true),
        refusals: ConnectRefusals = ConnectRefusals(),
    ) = ActivityViewModel(engine, MutableStateFlow(crash), UnconfinedTestDispatcher(testScheduler), { NOW }, { ZoneOffset.UTC }, online, refusals)

    private fun TestScope.collected(vm: ActivityViewModel) = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.ui.collect {} }

    @Test
    fun `the sample engine maps to the page model`() = runTest {
        val engine = rvSampleEngine()
        val vm = model(engine)
        collected(vm)
        val ui = vm.ui.value
        assertFalse(ui.loading)
        assertNull(ui.notice)
        assertEquals(RvRouteMode.Rule, ui.state.mode)
        // g-Proxy picks hk2, as RvSampleNodes builds it.
        assertEquals("Hong Kong 02 IPLC", ui.state.node?.name)
        assertEquals(ui.state.node?.id, ui.cards.mainNodeId)
        assertEquals(listOf("Streaming", null, "AI"), ui.rules.map { it.label })
        assertEquals(ui.rules.sortedByDescending { it.bytes }, ui.rules)
        assertEquals(2, ui.subscriptions.size)
        assertTrue(ui.nodes.all { it.history.size == 8 })
        assertEquals(12f, ui.cards.nowHour)
        assertEquals(engine.catalog.value?.ruleSource?.rules, ui.cards.ruleCount)
    }

    @Test
    fun `活動 never connects, whatever it is asked`() = runTest {
        val engine = Recording(FakeEngine())
        val vm = model(engine)
        collected(vm)
        listOf(ActivityAction.SetMode(Mode.Direct), ActivityAction.TestLatency, ActivityAction.Retry, ActivityAction.Disconnect).forEach(vm::onAction)
        engine.failMode = ErrorCode.Busy
        vm.onAction(ActivityAction.SetMode(Mode.Global))
        vm.onAction(ActivityAction.Retry)
        assertEquals(0, engine.connects)
    }

    @Test
    fun `usage is read every five seconds only while the page is shown`() = runTest {
        val engine = Recording(FakeEngine())
        val vm = model(engine)
        val shown = collected(vm)
        advanceTimeBy(ActivityViewModel.USAGE_MS * 3 + 1)
        assertEquals(4, engine.usageReads)
        shown.cancel()
        // WhileSubscribed keeps the upstream five seconds after the last collector leaves.
        advanceTimeBy(5_001)
        val stopped = engine.usageReads
        advanceTimeBy(ActivityViewModel.USAGE_MS * 10)
        assertEquals(stopped, engine.usageReads)
    }

    @Test
    fun `an unchanged usage read does not map the page again, a changed one does`() = runTest {
        val engine = Recording(rvSampleEngine())
        val vm = model(engine)
        val seen = mutableListOf<RvActivityUi>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.ui.collect { seen += it } }
        val first = seen.size
        advanceTimeBy(ActivityViewModel.USAGE_MS * 3 + 1)
        assertEquals(4, engine.usageReads)
        assertEquals(first, seen.size)
        val usage = checkNotNull(engine.usage().getOrNull())
        engine.nextUsage = usage.copy(today = usage.today.copy(down = usage.today.down.map { it + 1 }))
        advanceTimeBy(ActivityViewModel.USAGE_MS)
        assertEquals(first + 1, seen.size)
    }

    @Test
    fun `nodes are mapped once per catalog, not on each usage read`() = runTest {
        val engine = Recording(rvSampleEngine())
        val vm = model(engine)
        collected(vm)
        // One catalog: its nodes are read and mapped once.
        assertEquals(1, engine.nodeReads)
        advanceTimeBy(ActivityViewModel.USAGE_MS * 3 + 1)
        assertEquals(4, engine.usageReads)
        assertEquals(1, engine.nodeReads)
    }

    @Test
    fun `a failed node read keeps the last nodes`() = runTest {
        val fake = rvSampleEngine()
        val engine = Recording(fake)
        val vm = model(engine)
        collected(vm)
        val read = vm.ui.value.nodes.associate { it.id to it.host }
        assertTrue(read.values.all { it.isNotEmpty() })
        engine.failNodes = true
        // A new catalog revision: the same nodes under another order of groups.
        fake.groups.value = fake.groups.value.reversed()
        assertEquals(2, engine.nodeReads)
        // Each node keeps the endpoint the last good read gave it.
        assertEquals(read, vm.ui.value.nodes.associate { it.id to it.host })
    }

    @Test
    fun `no network shows the offline notice, and a refused consent comes first while not connected`() = runTest {
        val online = MutableStateFlow(false)
        val refusals = ConnectRefusals()
        val engine = rvSampleEngine().apply { status.value = Status.Stopped }
        val vm = model(engine, online = online, refusals = refusals)
        collected(vm)
        assertEquals(RvActivityNotice.Offline, vm.ui.value.notice)
        refusals.vpn.value = true
        assertEquals(RvActivityNotice.ConsentDenied, vm.ui.value.notice)
        refusals.vpn.value = false
        online.value = true
        assertNull(vm.ui.value.notice)
        refusals.notifications.value = true
        assertEquals(RvActivityNotice.NotifyDenied, vm.ui.value.notice)
    }

    @Test
    fun `rule rows are read again only when the rules change`() = runTest {
        val engine = Recording(rvSampleEngine())
        val vm = model(engine)
        collected(vm)
        advanceTimeBy(ActivityViewModel.USAGE_MS * 3 + 1)
        // One page holds the sample's rows; later usage reads find every id already resolved.
        assertEquals(1, engine.ruleReads)
    }

    @Test
    fun `a failed mode switch shows its banner until a retry succeeds`() = runTest {
        val engine = Recording(FakeEngine())
        val vm = model(engine)
        collected(vm)
        engine.failMode = ErrorCode.Busy
        vm.onAction(ActivityAction.SetMode(Mode.Direct))
        assertEquals(RvActivityFailure(RvActivityAttempt.SwitchMode, ErrorCode.Busy, ActivityAction.SetMode(Mode.Direct)), vm.ui.value.failure)
        engine.failMode = null
        vm.onAction(ActivityAction.Retry)
        assertNull(vm.ui.value.failure)
        assertEquals(RvRouteMode.Direct, vm.ui.value.state.mode)
    }

    @Test
    fun `a failed latency test shows its banner until the next test succeeds`() = runTest {
        val engine = Recording(FakeEngine())
        val vm = model(engine)
        collected(vm)
        engine.failTest = ErrorCode.Busy
        vm.onAction(ActivityAction.TestLatency)
        assertEquals(RvActivityAttempt.TestLatency, vm.ui.value.failure?.attempt)
        engine.failTest = null
        vm.onAction(ActivityAction.TestLatency)
        assertNull(vm.ui.value.failure)
    }

    @Test
    fun `a crash offer and a missing subscription are notices`() = runTest {
        val crashed = model(FakeEngine(), crash = true)
        collected(crashed)
        assertEquals(RvActivityNotice.Crash, crashed.ui.value.notice)
        val engine = FakeEngine()
        val empty = model(engine)
        collected(empty)
        engine.subscriptions.value = emptyList()
        assertEquals(RvActivityNotice.NoSubscription, empty.ui.value.notice)
        assertFalse(empty.ui.value.hasSubscriptions)
    }

    @Test
    fun `the page waits while the engine is not ready`() = runTest {
        val engine = FakeEngine()
        engine.ready.value = false
        val vm = model(engine)
        collected(vm)
        assertTrue(vm.ui.value.loading)
        engine.ready.value = true
        assertFalse(vm.ui.value.loading)
    }

    @Test
    fun `rows past the first page are read in pages of fifty until the ids are resolved`() = runTest {
        val all = List(120) { RuleRow("r-$it", it, RuleKind.Domain, "Domain", "d$it.example", 1, RuleTarget.Direct) }
        var reads = 0
        val engine = object : Engine by FakeEngine() {
            override suspend fun rules(offset: Int, limit: Int): Result<RulePage> {
                reads++
                return Result.success(RulePage(FakeRuleMode.SOURCE.rulesRevision, all.size, all.drop(offset).take(limit)))
            }
        }
        val reader = ActivityRuleReader(engine)
        assertEquals(setOf("r-3", "r-70"), reader.resolve(FakeRuleMode.SOURCE.rulesRevision, listOf("r-3", "r-70")).keys)
        assertEquals(2, reads)
        reader.resolve(FakeRuleMode.SOURCE.rulesRevision, listOf("r-3", "r-70"))
        assertEquals(2, reads)
        assertEquals(emptySet<String>(), reader.resolve(FakeRuleMode.SOURCE.rulesRevision, listOf("r-gone")).keys)
        assertEquals(3, reads)
        reader.resolve(FakeRuleMode.SOURCE.rulesRevision + 1, listOf("r-3"))
        // A new revision drops what was read; its first page differs from the engine's, so the read starts again once.
        assertTrue(reads > 3)
    }

    /** [sample] with today's `byRule` naming [ids] and 120 rule rows, counting each page read in [reads]. */
    private class ManyRules(private val sample: Engine, private val ids: List<String>, catalog: Catalog?) : Engine by sample {
        var reads = 0
        private val revision = sample.catalog.value?.ruleSource?.rulesRevision ?: 0L
        private val all = List(120) { RuleRow("r-$it", it, RuleKind.Domain, "Domain", "d$it.example", 1, RuleTarget.Direct) }
        override val catalog: StateFlow<Catalog?> = MutableStateFlow(catalog)

        override suspend fun usage(): Result<Usage> = sample.usage().map { it.copy(today = it.today.copy(byRule = ids.map { id -> RuleBytes(id, 1, 1, 1) })) }

        override suspend fun rules(offset: Int, limit: Int): Result<RulePage> {
            reads++
            return Result.success(RulePage(revision, all.size, all.drop(offset).take(limit)))
        }
    }

    @Test
    fun `unmatched and other are not looked up, so the read stops at the page that resolves the real ids`() = runTest {
        val sample = rvSampleEngine()
        val engine = ManyRules(sample, listOf("r-3", "unmatched", "other"), sample.catalog.value)
        collected(model(engine))
        advanceTimeBy(ActivityViewModel.USAGE_MS * 2 + 1)
        assertEquals(1, engine.reads)
    }

    @Test
    fun `without a catalog no rule is read`() = runTest {
        val engine = ManyRules(rvSampleEngine(), listOf("r-3", "r-70"), catalog = null)
        collected(model(engine))
        advanceTimeBy(ActivityViewModel.USAGE_MS * 2 + 1)
        assertEquals(0, engine.reads)
    }

    private companion object {
        /** 12:00 UTC. */
        const val NOW = 12L * 3_600_000
    }
}
