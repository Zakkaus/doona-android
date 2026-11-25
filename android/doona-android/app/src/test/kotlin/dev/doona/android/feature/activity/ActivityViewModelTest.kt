package dev.doona.android.feature.activity

import dev.doona.android.engine.Engine
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.Group
import dev.doona.android.engine.GroupKind
import dev.doona.android.engine.Member
import dev.doona.android.engine.Mode
import dev.doona.android.engine.Status
import dev.doona.android.engine.fake.FakeEngine
import dev.doona.android.feature.model.RvRouteMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
import java.time.ZoneOffset

/** [ActivityViewModel]'s actions and its page state over [FakeEngine]. */
@OptIn(ExperimentalCoroutinesApi::class)
class ActivityViewModelTest {
    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** `WhileSubscribed` only follows the engine while someone collects, as the page does. */
    private fun TestScope.collected(engine: Engine) =
        ActivityViewModel(engine, MutableStateFlow(false), UnconfinedTestDispatcher(testScheduler), { 0L }, { ZoneOffset.UTC }).also { vm ->
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.ui.collect {} }
        }

    @Test
    fun `follows the engine and resolves the node through nested groups`() = runTest {
        val engine = FakeEngine()
        val vm = collected(engine)
        engine.connect()
        val state = vm.ui.value.state
        assertEquals(Status.Running(sinceEpochMs = 0), state.status)
        assertEquals("n-369a8a63c1d2e3f4", state.node?.id)
    }

    @Test
    fun `disconnect stops the engine`() = runTest {
        val engine = FakeEngine(status = Status.Running(sinceEpochMs = 0))
        val vm = collected(engine)
        vm.onAction(ActivityAction.Disconnect)
        assertEquals(Status.Stopped, vm.ui.value.state.status)
    }

    @Test
    fun `set mode changes the mode`() = runTest {
        val vm = collected(FakeEngine())
        vm.onAction(ActivityAction.SetMode(Mode.Direct))
        assertEquals(RvRouteMode.Direct, vm.ui.value.state.mode)
        assertNull(vm.ui.value.failure)
    }

    @Test
    fun `a failed mode switch shows its failure until the next attempt succeeds`() = runTest {
        var fail = true
        val engine = object : Engine by FakeEngine() {
            override suspend fun setMode(mode: Mode): Result<Unit> = if (fail) Result.failure(EngineException(ErrorCode.Busy)) else Result.success(Unit)
        }
        val vm = collected(engine)
        vm.onAction(ActivityAction.SetMode(Mode.Direct))
        assertEquals(ErrorCode.Busy, vm.ui.value.failure?.code)
        assertEquals(RvRouteMode.Global, vm.ui.value.state.mode)
        fail = false
        vm.onAction(ActivityAction.SetMode(Mode.Direct))
        assertNull(vm.ui.value.failure)
    }

    @Test
    fun `a later success clears the error`() = runTest {
        var fail = true
        val fake = FakeEngine()
        val engine = object : Engine by fake {
            override suspend fun setMode(mode: Mode): Result<Unit> =
                if (fail) Result.failure(EngineException(ErrorCode.Busy)) else fake.setMode(mode)
        }
        val vm = collected(engine)
        vm.onAction(ActivityAction.SetMode(Mode.Direct))
        assertEquals(ErrorCode.Busy, vm.ui.value.failure?.code)
        fail = false
        vm.onAction(ActivityAction.SetMode(Mode.Direct))
        assertNull(vm.ui.value.failure)
        assertEquals(RvRouteMode.Direct, vm.ui.value.state.mode)
    }

    @Test
    fun `direct mode shows no active node`() = runTest {
        val vm = collected(FakeEngine(status = Status.Running(sinceEpochMs = 0)))
        vm.onAction(ActivityAction.SetMode(Mode.Direct))
        assertNull(vm.ui.value.state.node)
    }

    @Test
    fun `rule mode keeps the node of the flows no rule matched until the policy page shows rules`() = runTest {
        val vm = collected(FakeEngine(status = Status.Running(sinceEpochMs = 0), mode = Mode.Rule))
        assertEquals(RvRouteMode.Rule, vm.ui.value.state.mode)
        assertEquals("n-369a8a63c1d2e3f4", vm.ui.value.state.node?.id)
    }

    @Test
    fun `groups that select each other leave the node unknown instead of hanging`() = runTest {
        val a = Group("g-A", "A", GroupKind.Selector, listOf(Member("g-B", "B", isGroup = true)), selectedId = "g-B")
        val b = Group("g-B", "B", GroupKind.Selector, listOf(Member("g-A", "A", isGroup = true)), selectedId = "g-A")
        val vm = collected(FakeEngine(groups = listOf(a, b), status = Status.Running(sinceEpochMs = 0)))
        assertNull(vm.ui.value.state.node)
    }

    @Test
    fun `the page knows whether a subscription exists to start from`() = runTest {
        val engine = FakeEngine()
        val vm = collected(engine)
        assertTrue(vm.ui.value.hasSubscriptions)
        engine.subscriptions.value = emptyList()
        assertFalse(vm.ui.value.hasSubscriptions)
    }
}
