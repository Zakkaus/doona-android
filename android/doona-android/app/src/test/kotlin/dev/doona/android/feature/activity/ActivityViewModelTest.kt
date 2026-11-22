package dev.doona.android.feature.activity

import dev.doona.android.engine.Engine
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.Mode
import dev.doona.android.engine.Status
import dev.doona.android.engine.fake.FakeEngine
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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ActivityViewModelTest {
    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** `WhileSubscribed` only follows the engine while someone collects, as the screen does. */
    private fun TestScope.collected(engine: Engine) = ActivityViewModel(engine).also { vm ->
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
    }

    @Test
    fun `follows the engine and resolves the node through nested groups`() = runTest {
        val engine = FakeEngine()
        val vm = collected(engine)
        engine.connect()
        val state = vm.uiState.value
        assertEquals(Status.Running(sinceEpochMs = 0), state.status)
        assertEquals("g-Proxy", state.outboundGroupId)
        assertEquals("Auto", state.outbound)
        assertEquals("n-hk1", state.node?.id)
    }

    @Test
    fun `disconnect stops the engine`() = runTest {
        val engine = FakeEngine(status = Status.Running(sinceEpochMs = 0))
        val vm = collected(engine)
        vm.onAction(ActivityAction.Disconnect)
        assertEquals(Status.Stopped, vm.uiState.value.status)
    }

    @Test
    fun `set mode changes the mode`() = runTest {
        val vm = collected(FakeEngine())
        vm.onAction(ActivityAction.SetMode(Mode.Direct))
        assertEquals(Mode.Direct, vm.uiState.value.mode)
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun `a failed mode switch shows its code until dismissed`() = runTest {
        val engine = object : Engine by FakeEngine() {
            override suspend fun setMode(mode: Mode): Result<Unit> = Result.failure(EngineException(ErrorCode.Busy))
        }
        val vm = collected(engine)
        vm.onAction(ActivityAction.SetMode(Mode.Direct))
        assertEquals(ErrorCode.Busy, vm.uiState.value.error)
        assertEquals(Mode.Global, vm.uiState.value.mode)
        vm.onAction(ActivityAction.DismissError)
        assertNull(vm.uiState.value.error)
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
        assertEquals(ErrorCode.Busy, vm.uiState.value.error)
        fail = false
        vm.onAction(ActivityAction.SetMode(Mode.Direct))
        assertNull(vm.uiState.value.error)
        assertEquals(Mode.Direct, vm.uiState.value.mode)
    }

    @Test
    fun `direct mode shows no active node`() = runTest {
        val vm = collected(FakeEngine(status = Status.Running(sinceEpochMs = 0)))
        vm.onAction(ActivityAction.SetMode(Mode.Direct))
        assertNull(vm.uiState.value.node)
    }
}
