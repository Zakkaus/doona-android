package dev.doona.android.feature.policies

import dev.doona.android.engine.Catalog
import dev.doona.android.engine.Engine
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.RuleKind
import dev.doona.android.engine.RulePage
import dev.doona.android.engine.RuleRow
import dev.doona.android.engine.RuleTarget
import dev.doona.android.engine.fake.FakeEngine
import dev.doona.android.engine.fake.rvSampleEngine
import dev.doona.android.feature.model.RvRuleEnd
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class RulesViewModelTest {
    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.collected(engine: Engine) = RulesViewModel(engine, reads = UnconfinedTestDispatcher(testScheduler)).also { vm ->
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
    }

    /**
     * The sample engine with [rows] as its rule source when given, read in the pages the engine would return; [error] is
     * the source's compile error; [pageRevisions] are the revisions the next pages report; [failing] fails the next reads.
     */
    private class Source(val rows: List<RuleRow>? = null, error: ErrorCode? = null, val base: FakeEngine = rvSampleEngine()) : Engine by base {
        val reads = mutableListOf<Int>()
        val pageRevisions = ArrayDeque<Long>()
        var failing = 0
        val sourceError = MutableStateFlow(error)

        override val catalog: StateFlow<Catalog?> = combine(base.catalog, sourceError) { catalog, error ->
            catalog?.copy(ruleSource = catalog.ruleSource.copy(error = error, rulesRevision = REVISION))
        }.stateIn(CoroutineScope(Dispatchers.Unconfined), SharingStarted.Eagerly, null)

        override suspend fun rules(offset: Int, limit: Int): Result<RulePage> {
            reads += offset
            if (failing > 0) {
                failing--
                return Result.failure(EngineException(ErrorCode.Busy))
            }
            val all = rows ?: base.rules(0, MAX).getOrThrow().rows
            return Result.success(RulePage(pageRevisions.removeFirstOrNull() ?: REVISION, all.size, all.drop(offset).take(limit)))
        }
    }

    private fun rows(count: Int) = List(count) { RuleRow("r$it", it, RuleKind.DomainSuffix, "DomainSuffix", "s$it.example", 1, RuleTarget.Direct) }

    @Test
    fun `every row is read, a page at a time`() = runTest {
        val engine = Source(rows(120))
        val state = collected(engine).uiState.value
        assertFalse(state.loading)
        assertEquals(listOf(0, RULES_PAGE, RULES_PAGE * 2), engine.reads)
        assertEquals(120, state.rules.size)
        assertNull(state.failure)
    }

    @Test
    fun `a page at another revision starts the read over`() = runTest {
        val engine = Source(rows(60)).apply { pageRevisions += listOf(REVISION, REVISION + 1) }
        val state = collected(engine).uiState.value
        assertEquals(listOf(0, RULES_PAGE, 0, RULES_PAGE), engine.reads)
        assertEquals(60, state.rules.size)
    }

    @Test
    fun `the rules carry today's bytes and route through the catalog's groups, 拒絕 among them`() = runTest {
        val base = rvSampleEngine()
        val rows = withReject(base)
        val state = collected(Source(rows, base = base)).uiState.value
        val byRule = base.usage().getOrThrow().today.byRule.associate { it.id to it.up + it.down }
        assertEquals(rows.map { byRule[it.id] ?: 0L }, state.rules.map { it.bytes })
        assertEquals(rows.map { (it.target as? RuleTarget.Group)?.id }, state.rules.map { it.group?.id })
        assertEquals(RvRuleEnd.Reject, state.rules.last().end)
    }

    @Test
    fun `全部規則 lists as many rules as 策略's card counts, 拒絕 included`() = runTest {
        val base = rvSampleEngine()
        val engine = Source(withReject(base), base = base)
        val listed = collected(engine).uiState.value.rules.size
        val policies = PoliciesViewModel(engine, reads = UnconfinedTestDispatcher(testScheduler))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { policies.uiState.collect {} }
        assertEquals(listed, policies.uiState.value.profile?.ruleCount)
    }

    /** The sample's rules and one that refuses its flows last. */
    private suspend fun withReject(base: FakeEngine): List<RuleRow> {
        val sample = base.rules(0, MAX).getOrThrow().rows
        return sample + RuleRow("reject", sample.size, RuleKind.DomainSuffix, "DomainSuffix", "ads.example", 1, RuleTarget.Reject)
    }

    @Test
    fun `a source that did not compile shows why and reads nothing`() = runTest {
        val engine = Source(rows(3), ErrorCode.ParseFailed)
        val state = collected(engine).uiState.value
        assertEquals(RulesFailure.Parse, state.failure)
        assertEquals(emptyList<Int>(), engine.reads)
    }

    @Test
    fun `a failed read shows its reason and 重試 reads again`() = runTest {
        val engine = Source(rows(3)).apply { failing = 1 }
        val vm = collected(engine)
        assertEquals(RulesFailure.Read(ErrorCode.Busy), vm.uiState.value.failure)
        vm.retry()
        assertNull(vm.uiState.value.failure)
        assertEquals(3, vm.uiState.value.rules.size)
    }

    private companion object {
        const val REVISION = 7L
        const val MAX = 500
    }
}
