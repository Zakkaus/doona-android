package dev.doona.android.feature.policies

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import dev.doona.android.engine.Engine
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.Group
import dev.doona.android.engine.GroupKind
import dev.doona.android.engine.Member
import dev.doona.android.engine.Network
import dev.doona.android.engine.fake.FakeEngine
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

    /** `WhileSubscribed` only follows the engine while someone collects, as the screen does. */
    private fun TestScope.collected(engine: Engine, groupId: String) = GroupViewModel(engine, groupId).also { vm ->
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
    }

    /** An engine whose select and probe answer with [result], recording what they were asked. */
    private class Answering(var result: Result<Unit>, val base: FakeEngine = FakeEngine()) : Engine by base {
        val selected = mutableListOf<Pair<String, String?>>()
        val probed = mutableListOf<String?>()

        override suspend fun select(groupId: String, memberId: String?, network: Network?): Result<Unit> {
            selected += groupId to memberId
            return if (result.isSuccess) base.select(groupId, memberId, network) else result
        }

        override suspend fun testLatency(groupId: String?): Result<Unit> {
            probed += groupId
            return result
        }
    }

    private fun failure(code: ErrorCode = ErrorCode.Busy) = Result.failure<Unit>(EngineException(code))

    @Test
    fun `shows the group, what it uses now and the node under it`() = runTest {
        val detail = collected(FakeEngine(), "g-Proxy").uiState.value.detail
        assertNotNull(detail)
        assertEquals("Proxy", detail!!.group.name)
        assertEquals("g-Auto", detail.current?.id)
        assertEquals("n-369a8a63c1d2e3f4", detail.currentNode?.id)
    }

    @Test
    fun `lists the groups inside first and the nodes fastest first, untested and failed last`() = runTest {
        val members = listOf(
            Member("n-slow", "slow", latencyMs = 80),
            Member("n-untested", "untested"),
            Member("g-Inner", "Inner", isGroup = true),
            Member("n-fast", "fast", latencyMs = 20),
            Member("n-failed", "failed", failed = true),
        )
        val outer = Group("g-Outer", "Outer", GroupKind.Selector, members, selectedId = "n-fast")
        val inner = Group("g-Inner", "Inner", GroupKind.UrlTest, members.take(1), selectedId = "n-slow")
        val detail = collected(FakeEngine(groups = listOf(outer, inner)), "g-Outer").uiState.value.detail!!
        assertEquals(listOf("g-Inner", "n-fast", "n-slow", "n-untested", "n-failed"), detail.members.map { it.member.id })
        assertEquals(listOf("g-Inner"), detail.subGroups.map { it.id })
        assertEquals("n-slow", detail.members.first().node?.id)
    }

    @Test
    fun `an unknown group has no page once the engine is ready`() = runTest {
        val state = collected(FakeEngine(), "g-Gone").uiState.value
        assertNull(state.detail)
        assertTrue(state.ready)
    }

    @Test
    fun `a group that disappears leaves the page empty`() = runTest {
        val engine = FakeEngine()
        val vm = collected(engine, "g-HK")
        assertNotNull(vm.uiState.value.detail)
        engine.groups.value = engine.groups.value.filterNot { it.id == "g-HK" }
        assertNull(vm.uiState.value.detail)
    }

    @Test
    fun `selecting a member pins it`() = runTest {
        val engine = Answering(Result.success(Unit))
        val vm = collected(engine, "g-Proxy")
        vm.onAction(GroupAction.Select("n-c2f5a8e1b3c4d5e6"))
        assertEquals(listOf("g-Proxy" to "n-c2f5a8e1b3c4d5e6"), engine.selected)
        val group = vm.uiState.value.detail!!.group
        assertEquals("n-c2f5a8e1b3c4d5e6", group.selectedId)
        assertTrue(group.pinned)
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun `a failed select shows its code and leaves the choice`() = runTest {
        val vm = collected(Answering(failure(ErrorCode.NotFound)), "g-Proxy")
        vm.onAction(GroupAction.Select("n-c2f5a8e1b3c4d5e6"))
        assertEquals(ErrorCode.NotFound, vm.uiState.value.error)
        assertEquals("g-Auto", vm.uiState.value.detail!!.group.selectedId)
        vm.onAction(GroupAction.DismissError)
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun `an automatic group offers the way back only while pinned`() = runTest {
        val vm = collected(FakeEngine(), "g-Auto")
        assertFalse(vm.uiState.value.detail!!.canReturnToAutomatic)
        vm.onAction(GroupAction.Select("n-85d011c7e5f60718"))
        assertTrue(vm.uiState.value.detail!!.canReturnToAutomatic)
    }

    @Test
    fun `a selector never offers a way back to automatic`() = runTest {
        val vm = collected(FakeEngine(), "g-Proxy")
        vm.onAction(GroupAction.Select("n-85d011c7e5f60718"))
        assertFalse(vm.uiState.value.detail!!.canReturnToAutomatic)
    }

    @Test
    fun `return to automatic hands the group back to its own choice`() = runTest {
        val engine = Answering(Result.success(Unit))
        val vm = collected(engine, "g-Auto")
        vm.onAction(GroupAction.Select("n-85d011c7e5f60718"))
        vm.onAction(GroupAction.ReturnToAutomatic)
        assertEquals("g-Auto" to null, engine.selected.last())
        assertFalse(vm.uiState.value.detail!!.group.pinned)
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun `a failed return to automatic keeps the pin and shows its code`() = runTest {
        val engine = Answering(Result.success(Unit))
        val vm = collected(engine, "g-Auto")
        vm.onAction(GroupAction.Select("n-85d011c7e5f60718"))
        engine.result = failure(ErrorCode.Internal)
        vm.onAction(GroupAction.ReturnToAutomatic)
        assertEquals(ErrorCode.Internal, vm.uiState.value.error)
        assertTrue(vm.uiState.value.detail!!.group.pinned)
    }

    @Test
    fun `test probes this group only`() = runTest {
        val engine = Answering(Result.success(Unit))
        val vm = collected(engine, "g-HK")
        vm.onAction(GroupAction.Test)
        assertEquals(listOf<String?>("g-HK"), engine.probed)
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun `a failed test shows its code and a later success clears it`() = runTest {
        val engine = Answering(failure(ErrorCode.Busy))
        val vm = collected(engine, "g-HK")
        vm.onAction(GroupAction.Test)
        assertEquals(ErrorCode.Busy, vm.uiState.value.error)
        engine.result = Result.success(Unit)
        vm.onAction(GroupAction.Test)
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun `follows the probe while it runs`() = runTest {
        val engine = FakeEngine()
        val vm = collected(engine, "g-Auto")
        assertFalse(vm.uiState.value.detail!!.group.testing)
        engine.groups.value = engine.groups.value.map { it.copy(testing = it.id == "g-Auto") }
        assertTrue(vm.uiState.value.detail!!.group.testing)
    }

    /** An engine whose first select waits for [gate]; every call is logged as start and end. */
    private class SlowFirstSelect(val gate: CompletableDeferred<Unit>, val base: FakeEngine = FakeEngine()) : Engine by base {
        val log = mutableListOf<String>()
        private var calls = 0

        override suspend fun select(groupId: String, memberId: String?, network: Network?): Result<Unit> {
            log += "start $memberId"
            if (calls++ == 0) gate.await()
            return base.select(groupId, memberId, network).also { log += "end $memberId" }
        }

        override suspend fun testLatency(groupId: String?): Result<Unit> {
            log += "test $groupId"
            return Result.success(Unit)
        }
    }

    @Test
    fun `taps are handed to the engine at once and in tap order`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val engine = SlowFirstSelect(gate)
        val vm = collected(engine, "g-Proxy")
        vm.onAction(GroupAction.Select("n-369a8a63c1d2e3f4"))
        vm.onAction(GroupAction.Select("n-c2f5a8e1b3c4d5e6"))
        assertEquals(listOf("start n-369a8a63c1d2e3f4", "start n-c2f5a8e1b3c4d5e6"), engine.log.filter { it.startsWith("start") })
        gate.complete(Unit)
    }

    @Test
    fun `a select, a return to automatic and a test are handed over in tap order`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val engine = SlowFirstSelect(gate)
        val vm = collected(engine, "g-Auto")
        vm.onAction(GroupAction.Select("n-85d011c7e5f60718"))
        vm.onAction(GroupAction.ReturnToAutomatic)
        vm.onAction(GroupAction.Test)
        assertEquals(listOf("start n-85d011c7e5f60718", "start null", "test g-Auto"), engine.log.filter { !it.startsWith("end") })
        gate.complete(Unit)
    }

    @Test
    fun `leaving the screen with a tap waiting does not lose it`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val engine = SlowFirstSelect(gate)
        val store = ViewModelStore()
        val vm = ViewModelProvider(store, GroupViewModel.factory(engine, "g-Proxy"))[GroupViewModel::class.java]
        vm.onAction(GroupAction.Select("n-369a8a63c1d2e3f4"))
        vm.onAction(GroupAction.Select("n-c2f5a8e1b3c4d5e6"))
        store.clear()
        // The engine has both, in order, whether or not the screen is still there to see the answers.
        assertEquals(listOf("start n-369a8a63c1d2e3f4", "start n-c2f5a8e1b3c4d5e6"), engine.log.filter { it.startsWith("start") })
        gate.complete(Unit)
    }

    @Test
    fun `a failed tap does not stop the taps behind it`() = runTest {
        var calls = 0
        val fake = FakeEngine()
        val engine = object : Engine by fake {
            override suspend fun select(groupId: String, memberId: String?, network: Network?): Result<Unit> =
                if (calls++ == 0) failure(ErrorCode.Busy) else fake.select(groupId, memberId, network)
        }
        val vm = collected(engine, "g-Proxy")
        vm.onAction(GroupAction.Select("n-369a8a63c1d2e3f4"))
        vm.onAction(GroupAction.Select("n-c2f5a8e1b3c4d5e6"))
        assertEquals("n-c2f5a8e1b3c4d5e6", vm.uiState.value.detail!!.group.selectedId)
        assertNull(vm.uiState.value.error)
    }
}
