package dev.doona.android.feature.policies

import dev.doona.android.engine.Catalog
import dev.doona.android.engine.Engine
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.Mode
import dev.doona.android.engine.Network
import dev.doona.android.engine.RuleOptions
import dev.doona.android.engine.RulePage
import dev.doona.android.engine.fake.FakeEngine
import dev.doona.android.engine.fake.rvSampleEngine
import dev.doona.android.feature.model.RvNodeSort
import dev.doona.android.feature.model.RvRouteMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
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
class PoliciesViewModelTest {
    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** `WhileSubscribed` only follows the engine while someone collects, as the screen does; reads run in the test. */
    private fun TestScope.collected(engine: Engine) =
        PoliciesViewModel(engine, clock = { NOW }, reads = UnconfinedTestDispatcher(testScheduler)).also { vm ->
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        }

    /**
     * The sample engine, recording the calls 策略 makes and answering them with [result]. [revision] overrides the catalog's
     * rules revision; [pageRevisions] are the revisions the next rule pages report, for a page read while the source changed.
     */
    private class Recording(val base: FakeEngine = rvSampleEngine()) : Engine by base {
        var result: Result<Unit> = Result.success(Unit)
        val calls = mutableListOf<String>()
        val revision = MutableStateFlow<Long?>(null)
        val pageRevisions = ArrayDeque<Long>()
        var ruleReads = 0

        override val catalog: StateFlow<Catalog?> = combine(base.catalog, revision) { catalog, revision ->
            if (catalog == null || revision == null) catalog else catalog.copy(ruleSource = catalog.ruleSource.copy(rulesRevision = revision))
        }.stateIn(CoroutineScope(Dispatchers.Unconfined), SharingStarted.Eagerly, null)

        override suspend fun setMode(mode: Mode): Result<Unit> = answer("setMode $mode") { base.setMode(mode) }

        override suspend fun setRuleSource(template: String, options: RuleOptions): Result<Unit> =
            answer("setRuleSource $template ${options.blockQuic}") { base.setRuleSource(template, options) }

        override suspend fun select(groupId: String, memberId: String?, network: Network?): Result<Unit> =
            answer("select $groupId $memberId $network") { base.select(groupId, memberId, network) }

        override suspend fun testLatency(groupId: String?): Result<Unit> = answer("test $groupId") { Result.success(Unit) }

        override suspend fun rules(offset: Int, limit: Int): Result<RulePage> {
            ruleReads++
            val page = base.rules(offset, limit).getOrThrow()
            return Result.success(page.copy(rulesRevision = pageRevisions.removeFirstOrNull() ?: catalog.value!!.ruleSource.rulesRevision))
        }

        private suspend fun answer(call: String, apply: suspend () -> Result<Unit>): Result<Unit> {
            calls += call
            return if (result.isSuccess) apply() else result
        }
    }

    @Test
    fun `a tap on the selected segment calls nothing`() = runTest {
        val engine = Recording()
        val vm = collected(engine)
        val state = vm.uiState.value.state
        assertEquals(RvRouteMode.Rule, state.mode)
        vm.onChange(state.copy(mode = RvRouteMode.Rule))
        assertEquals(emptyList<String>(), engine.calls)
    }

    @Test
    fun `a mode switch calls setMode once and shows the engine's mode`() = runTest {
        val engine = Recording()
        val vm = collected(engine)
        vm.onChange(vm.uiState.value.state.copy(mode = RvRouteMode.Global))
        assertEquals(listOf("setMode Global"), engine.calls)
        assertEquals(RvRouteMode.Global, vm.uiState.value.state.mode)
    }

    @Test
    fun `blocking QUIC keeps the template and sets the option`() = runTest {
        val engine = Recording()
        val vm = collected(engine)
        vm.onChange(vm.uiState.value.state.copy(blockQuic = true))
        assertEquals(listOf("setRuleSource cn-direct true"), engine.calls)
        assertTrue(vm.uiState.value.state.blockQuic)
    }

    @Test
    fun `the order and the ad switch stay on the page`() = runTest {
        val engine = Recording()
        val vm = collected(engine)
        val state = vm.uiState.value.state
        assertFalse(state.blockAds)
        vm.onChange(state.copy(blockAds = true, nodeSort = RvNodeSort.Name))
        assertEquals(emptyList<String>(), engine.calls)
        assertTrue(vm.uiState.value.state.blockAds)
        assertEquals(RvNodeSort.Name, vm.uiState.value.state.nodeSort)
    }

    @Test
    fun `choosing the main node selects it in the main group on both networks`() = runTest {
        val engine = Recording()
        val vm = collected(engine)
        val main = vm.uiState.value.profile!!.main
        val other = main.members.first { it.id != main.selectedId }.id
        vm.onChange(vm.uiState.value.state.choose(main.id, main.selectedId))
        assertEquals(emptyList<String>(), engine.calls)
        vm.onChange(vm.uiState.value.state.choose(main.id, other))
        assertEquals(listOf("select g-Proxy $other null"), engine.calls)
        assertEquals(other, vm.uiState.value.profile!!.main.selectedId)
    }

    @Test
    fun `a failure shows in the banner until the next action succeeds`() = runTest {
        val engine = Recording()
        val vm = collected(engine)
        engine.result = Result.failure(EngineException(ErrorCode.Busy))
        vm.testAll()
        assertEquals(PolicyFailure(PolicyAction.TestAll, ErrorCode.Busy), vm.uiState.value.failure)
        engine.result = Result.success(Unit)
        vm.onChange(vm.uiState.value.state.copy(mode = RvRouteMode.Direct))
        assertNull(vm.uiState.value.failure)
        assertEquals(listOf("test null", "setMode Direct"), engine.calls)
    }

    @Test
    fun `loading until the engine is ready and the profile is read`() = runTest {
        val engine = Recording()
        engine.base.ready.value = false
        val vm = collected(engine)
        assertTrue(vm.uiState.value.loading)
        engine.base.ready.value = true
        assertFalse(vm.uiState.value.loading)
        assertNotNull(vm.uiState.value.profile)
    }

    @Test
    fun `no subscription when the catalog has none`() = runTest {
        val vm = collected(Recording(FakeEngine(subscriptions = emptyList())))
        assertTrue(vm.uiState.value.noSubscription)
        assertFalse(collected(Recording()).uiState.value.noSubscription)
    }

    @Test
    fun `the rules are read again only when their revision changes`() = runTest {
        val engine = Recording()
        val vm = collected(engine)
        assertEquals(1, engine.ruleReads)
        val main = vm.uiState.value.profile!!.main
        vm.onChange(vm.uiState.value.state.choose(main.id, main.members.first { it.id != main.selectedId }.id))
        assertEquals(1, engine.ruleReads)
        engine.revision.value = 2L
        assertEquals(2, engine.ruleReads)
        assertNotNull(vm.uiState.value.profile)
    }

    @Test
    fun `a stale page of rules is read again`() = runTest {
        val engine = Recording()
        engine.pageRevisions += 99L
        val vm = collected(engine)
        assertEquals(2, engine.ruleReads)
        assertEquals(RV_POLICY_CARD_RULES, vm.uiState.value.profile!!.rules.size)
    }

    @Test
    fun `updating refreshes every subscription in order`() = runTest {
        val updated = mutableListOf<String>()
        val engine = object : Engine by rvSampleEngine() {
            override suspend fun updateSubscription(id: String) = Result.success(catalog.value!!.subscriptions.first { it.id == id }).also { updated += id }
        }
        collected(engine).updateSubscriptions()
        assertEquals(listOf("s1", "s2"), updated)
    }

    private companion object {
        const val NOW = 1_764_000_000_000L
    }
}
