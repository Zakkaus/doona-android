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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OldPoliciesViewModelTest {
    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** `WhileSubscribed` only follows the engine while someone collects, as the screen does. */
    private fun TestScope.collected(engine: Engine) = OldPoliciesViewModel(engine).also { vm ->
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
    }

    /** An engine whose probe answers with [result] and records which group it was asked about. */
    private class Probing(var result: Result<Unit>, base: Engine = FakeEngine()) : Engine by base {
        val asked = mutableListOf<String?>()

        override suspend fun testLatency(groupId: String?): Result<Unit> {
            asked += groupId
            return result
        }
    }

    @Test
    fun `shows each group with its current member and the node under it`() = runTest {
        val vm = collected(FakeEngine(FakeEngine.SAMPLE_GROUPS))
        val items = vm.uiState.value.items
        assertEquals(listOf("g-Proxy", "g-Auto", "g-HK"), items.map { it.group.id })
        assertEquals("g-Auto", items[0].current?.id)
        assertEquals("n-369a8a63c1d2e3f4", items[0].node?.id)
        assertEquals(42, items[0].node?.latencyMs)
        assertEquals("n-369a8a63c1d2e3f4", items[1].current?.id)
    }

    @Test
    fun `follows the engine while a group is being probed`() = runTest {
        val engine = FakeEngine()
        val vm = collected(engine)
        assertFalse(vm.uiState.value.items.any { it.group.testing })
        engine.groups.value = engine.groups.value.map { it.copy(testing = it.id == "g-Auto") }
        assertEquals(listOf("g-Auto"), vm.uiState.value.items.filter { it.group.testing }.map { it.group.id })
    }

    @Test
    fun `a group whose selection leads nowhere has no node`() = runTest {
        val dangling = Group("g-A", "A", GroupKind.Selector, listOf(Member("g-Gone", "Gone", isGroup = true)), selectedId = "g-Gone")
        val none = Group("g-B", "B", GroupKind.Selector, emptyList(), selectedId = null)
        val vm = collected(FakeEngine(groups = listOf(dangling, none)))
        val items = vm.uiState.value.items
        assertEquals("g-Gone", items[0].current?.id)
        assertNull(items[0].node)
        assertNull(items[1].current)
    }

    @Test
    fun `groups that select each other do not hang the list`() = runTest {
        val a = Group("g-A", "A", GroupKind.Selector, listOf(Member("g-B", "B", isGroup = true)), selectedId = "g-B")
        val b = Group("g-B", "B", GroupKind.Selector, listOf(Member("g-A", "A", isGroup = true)), selectedId = "g-A")
        val vm = collected(FakeEngine(groups = listOf(a, b)))
        assertEquals(listOf(null, null), vm.uiState.value.items.map { it.node })
    }

    @Test
    fun `is not ready until the engine has loaded`() = runTest {
        val engine = FakeEngine().apply { ready.value = false }
        val vm = collected(engine)
        assertFalse(vm.uiState.value.ready)
        engine.ready.value = true
        assertTrue(vm.uiState.value.ready)
    }

    @Test
    fun `the filter narrows the list to selectors or to automatic groups`() = runTest {
        val vm = collected(FakeEngine(FakeEngine.SAMPLE_GROUPS))
        vm.onAction(OldPoliciesAction.SetFilter(GroupFilter.Manual))
        assertEquals(listOf("g-Proxy"), vm.uiState.value.visible.map { it.group.id })
        vm.onAction(OldPoliciesAction.SetFilter(GroupFilter.Auto))
        assertEquals(listOf("g-Auto", "g-HK"), vm.uiState.value.visible.map { it.group.id })
        assertEquals(1, vm.uiState.value.manual)
    }

    @Test
    fun `test all probes every group`() = runTest {
        val engine = Probing(Result.success(Unit))
        val vm = collected(engine)
        vm.onAction(OldPoliciesAction.TestAll)
        assertEquals(listOf<String?>(null), engine.asked)
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun `a failed test shows its code until dismissed`() = runTest {
        val vm = collected(Probing(Result.failure(EngineException(ErrorCode.Busy))))
        vm.onAction(OldPoliciesAction.TestAll)
        assertEquals(ErrorCode.Busy, vm.uiState.value.error)
        vm.onAction(OldPoliciesAction.DismissError)
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun `a later success clears the error`() = runTest {
        val engine = Probing(Result.failure(EngineException(ErrorCode.Busy)))
        val vm = collected(engine)
        vm.onAction(OldPoliciesAction.TestAll)
        assertEquals(ErrorCode.Busy, vm.uiState.value.error)
        engine.result = Result.success(Unit)
        vm.onAction(OldPoliciesAction.TestAll)
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun `an error that is not an engine error is shown as internal`() = runTest {
        val vm = collected(Probing(Result.failure(IllegalStateException("boom"))))
        vm.onAction(OldPoliciesAction.TestAll)
        assertEquals(ErrorCode.Internal, vm.uiState.value.error)
    }
}
