package dev.doona.android.feature.activity

import dev.doona.android.engine.Engine
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.Mode
import dev.doona.android.engine.Status
import dev.doona.android.engine.fake.FakeEngine
import dev.doona.android.feature.model.RvRouteMode
import dev.doona.android.honk.ActivityHonkFixtures
import dev.doona.android.honk.ActivityHonkFixtures.empty
import dev.doona.android.honk.ActivityHonkFixtures.refusingProbes
import dev.doona.android.honk.FakeHonk
import dev.doona.android.honk.HonkExamples
import dev.doona.android.honk.HonkFixtures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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

/** [ActivityViewModel]: 活動 over honk ([ActivityHonkFixtures.sample]), with the tunnel's status and the mode from [FakeEngine]. */
@OptIn(ExperimentalCoroutinesApi::class)
class ActivityViewModelTest {
    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** Counts what 活動 must not do on its own (connect); [failMode] fails every later mode switch. */
    private class Recording(private val fake: Engine = FakeEngine(status = Status.Running(sinceEpochMs = 0), mode = Mode.Rule)) : Engine by fake {
        var connects = 0
        var failMode: ErrorCode? = null

        override fun connect() {
            connects++
        }

        override suspend fun setMode(mode: Mode): Result<Unit> = failMode?.let { Result.failure(EngineException(it)) } ?: fake.setMode(mode)
    }

    /** The ViewModel with its profile read, followed while collected as the page does (`WhileSubscribed`). */
    private fun TestScope.shown(
        honk: FakeHonk = ActivityHonkFixtures.sample(),
        engine: Engine = Recording(),
        crash: Boolean = false,
        online: Flow<Boolean> = flowOf(true),
        refusals: ConnectRefusals = ConnectRefusals(),
    ): ActivityViewModel {
        val work = UnconfinedTestDispatcher(testScheduler)
        val vm = ActivityViewModel(engine, honk, MutableStateFlow(crash), work, { NOW }, { ZoneOffset.UTC }, online, refusals, io = work)
        vm.load()
        backgroundScope.launch(work) { vm.ui.collect {} }
        return vm
    }

    private fun FakeHonk.reads(path: String) = calls.count { it.method == "GET" && it.path == path }

    @Test
    fun `the sample maps to the page model`() = runTest {
        val ui = shown().ui.value
        assertFalse(ui.loading)
        assertNull(ui.notice)
        assertEquals(RvRouteMode.Rule, ui.state.mode)
        assertEquals("Hong Kong 02 IPLC", ui.state.node?.name)
        assertEquals(ui.state.node?.id, ui.cards.mainNodeId)
        assertEquals(listOf("Streaming", null, "AI"), ui.rules.map { it.label })
        assertEquals(2, ui.subscriptions.size)
        assertEquals(48, ui.nodes.size)
        assertEquals(12f, ui.cards.nowHour)
        assertEquals(14, ui.cards.ruleCount)
        assertTrue(ui.state.downRate > 0)
        assertEquals(60, ui.state.downHistory.size)
    }

    @Test
    fun `活動 never connects, whatever it is asked`() = runTest {
        val engine = Recording()
        val vm = shown(engine = engine)
        listOf(ActivityAction.SetMode(Mode.Direct), ActivityAction.TestLatency, ActivityAction.Retry, ActivityAction.Disconnect).forEach(vm::onAction)
        engine.failMode = ErrorCode.Busy
        vm.onAction(ActivityAction.SetMode(Mode.Global))
        vm.onAction(ActivityAction.Retry)
        assertEquals(0, engine.connects)
    }

    @Test
    fun `disconnect stops the tunnel`() = runTest {
        val vm = shown()
        vm.onAction(ActivityAction.Disconnect)
        assertEquals(Status.Stopped, vm.ui.value.state.status)
    }

    @Test
    fun `direct mode shows no active node`() = runTest {
        val vm = shown()
        vm.onAction(ActivityAction.SetMode(Mode.Direct))
        assertEquals(RvRouteMode.Direct, vm.ui.value.state.mode)
        assertNull(vm.ui.value.state.node)
    }

    @Test
    fun `the bytes per outbound are read every five seconds and the traffic every second, only while the page is shown`() = runTest {
        val honk = ActivityHonkFixtures.sample()
        val work = UnconfinedTestDispatcher(testScheduler)
        val vm = ActivityViewModel(Recording(), honk, MutableStateFlow(false), work, { NOW }, { ZoneOffset.UTC }, io = work)
        val shown = backgroundScope.launch(work) { vm.ui.collect {} }
        advanceTimeBy(ActivityViewModel.USAGE_MS * 3 + 1)
        assertEquals(4, honk.reads(HonkExamples.OUTBOUNDS_PATH))
        assertEquals(16, honk.reads(HonkExamples.RUNTIME_PATH))
        shown.cancel()
        // WhileSubscribed keeps the upstream five seconds after the last collector leaves.
        advanceTimeBy(5_001)
        val stopped = honk.calls.size
        advanceTimeBy(ActivityViewModel.USAGE_MS * 10)
        assertEquals(stopped, honk.calls.size)
    }

    @Test
    fun `the profile is read once per load, not on each outbound read`() = runTest {
        val honk = ActivityHonkFixtures.sample()
        shown(honk)
        advanceTimeBy(ActivityViewModel.USAGE_MS * 3 + 1)
        assertEquals(1, honk.reads("/api/v1/rules"))
        assertEquals(1, honk.reads("/api/v1/nodes"))
    }

    @Test
    fun `a failed read keeps what was shown`() = runTest {
        val honk = ActivityHonkFixtures.sample()
        val vm = shown(honk)
        val nodes = vm.ui.value.nodes
        honk.on("GET", "/api/v1/nodes", 503, HonkExamples.UNAVAILABLE)
        honk.on("GET", HonkExamples.OUTBOUNDS_PATH, 503, HonkExamples.UNAVAILABLE)
        vm.load()
        advanceTimeBy(ActivityViewModel.USAGE_MS + 1)
        assertEquals(nodes, vm.ui.value.nodes)
        assertEquals(listOf("Streaming", null, "AI"), vm.ui.value.rules.map { it.label })
    }

    @Test
    fun `no network shows the offline notice, and a refused consent comes first while not connected`() = runTest {
        val online = MutableStateFlow(false)
        val refusals = ConnectRefusals()
        val vm = shown(engine = Recording(FakeEngine(mode = Mode.Rule)), online = online, refusals = refusals)
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
    fun `a failed mode switch shows its banner until a retry succeeds`() = runTest {
        val engine = Recording()
        val vm = shown(engine = engine)
        engine.failMode = ErrorCode.Busy
        vm.onAction(ActivityAction.SetMode(Mode.Direct))
        assertEquals(RvActivityFailure(RvActivityAttempt.SwitchMode, ErrorCode.Busy, ActivityAction.SetMode(Mode.Direct)), vm.ui.value.failure)
        engine.failMode = null
        vm.onAction(ActivityAction.Retry)
        assertNull(vm.ui.value.failure)
        assertEquals(RvRouteMode.Direct, vm.ui.value.state.mode)
    }

    @Test
    fun `a latency test probes every group and reads the profile again, and a refused one shows its banner until the next succeeds`() = runTest {
        val honk = ActivityHonkFixtures.sample().refusingProbes()
        val vm = shown(honk)
        vm.onAction(ActivityAction.TestLatency)
        assertEquals(RvActivityFailure(RvActivityAttempt.TestLatency, ErrorCode.Busy, ActivityAction.TestLatency), vm.ui.value.failure)
        honk.on("POST", ActivityHonkFixtures.PROBES_PATH, 202, HonkFixtures.body("probes/queued"))
        honk.on("GET", "/api/v1/operations/op-01HZX4K8W9", 200, HonkFixtures.body("operations/probe-complete"))
        val probes = honk.calls.count { it.method == "POST" }
        vm.onAction(ActivityAction.Retry)
        // honk's operations are polled a second apart, one group after another.
        advanceTimeBy(8 * 1_000L + 1)
        assertNull(vm.ui.value.failure)
        assertEquals(8, honk.calls.count { it.method == "POST" } - probes)
        assertEquals(3, honk.reads("/api/v1/rules"))
    }

    @Test
    fun `a crash offer and a profile with no subscription and no node are notices`() = runTest {
        assertEquals(RvActivityNotice.Crash, shown(crash = true).ui.value.notice)
        val empty = shown(ActivityHonkFixtures.sample().empty())
        assertEquals(RvActivityNotice.NoSubscription, empty.ui.value.notice)
        assertFalse(empty.ui.value.hasSubscriptions)
    }

    @Test
    fun `the page waits while the engine is not ready`() = runTest {
        val engine = FakeEngine(mode = Mode.Rule)
        engine.ready.value = false
        val vm = shown(engine = engine)
        assertTrue(vm.ui.value.loading)
        engine.ready.value = true
        assertFalse(vm.ui.value.loading)
    }

    private companion object {
        /** Noon UTC. */
        const val NOW = 12L * 3_600_000
    }
}
