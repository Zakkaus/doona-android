package dev.doona.android.feature.policies

import dev.doona.android.engine.Engine
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.Group
import dev.doona.android.engine.GroupKind
import dev.doona.android.engine.Member
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

        override suspend fun select(groupId: String, memberId: String?): Result<Unit> {
            selected += groupId to memberId
            return if (result.isSuccess) base.select(groupId, memberId) else result
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
        assertEquals("n-hk1", detail.currentNode?.id)
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
        vm.onAction(GroupAction.Select("n-us1"))
        assertEquals(listOf("g-Proxy" to "n-us1"), engine.selected)
        val group = vm.uiState.value.detail!!.group
        assertEquals("n-us1", group.selectedId)
        assertTrue(group.pinned)
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun `a failed select shows its code and leaves the choice`() = runTest {
        val vm = collected(Answering(failure(ErrorCode.NotFound)), "g-Proxy")
        vm.onAction(GroupAction.Select("n-us1"))
        assertEquals(ErrorCode.NotFound, vm.uiState.value.error)
        assertEquals("g-Auto", vm.uiState.value.detail!!.group.selectedId)
        vm.onAction(GroupAction.DismissError)
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun `an automatic group offers the way back only while pinned`() = runTest {
        val vm = collected(FakeEngine(), "g-Auto")
        assertFalse(vm.uiState.value.detail!!.canReturnToAutomatic)
        vm.onAction(GroupAction.Select("n-jp1"))
        assertTrue(vm.uiState.value.detail!!.canReturnToAutomatic)
    }

    @Test
    fun `a selector never offers a way back to automatic`() = runTest {
        val vm = collected(FakeEngine(), "g-Proxy")
        vm.onAction(GroupAction.Select("n-jp1"))
        assertFalse(vm.uiState.value.detail!!.canReturnToAutomatic)
    }

    @Test
    fun `return to automatic hands the group back to its own choice`() = runTest {
        val engine = Answering(Result.success(Unit))
        val vm = collected(engine, "g-Auto")
        vm.onAction(GroupAction.Select("n-jp1"))
        vm.onAction(GroupAction.ReturnToAutomatic)
        assertEquals("g-Auto" to null, engine.selected.last())
        assertFalse(vm.uiState.value.detail!!.group.pinned)
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun `a failed return to automatic keeps the pin and shows its code`() = runTest {
        val engine = Answering(Result.success(Unit))
        val vm = collected(engine, "g-Auto")
        vm.onAction(GroupAction.Select("n-jp1"))
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
}
