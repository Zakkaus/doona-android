package dev.doona.android.feature.stats

import dev.doona.android.engine.Engine
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.Subscription
import dev.doona.android.engine.Usage
import dev.doona.android.engine.fake.rvSampleEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class StatsViewModelTest {
    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    /**
     * The sample engine, counting its reads and calls, with `usage()` failing while [failUsage] is set, the latency test
     * failing with [testFails] and the update of each id in [updateFails] with its code.
     */
    private class Counting(private val engine: Engine = rvSampleEngine()) : Engine by engine {
        var usageReads = 0
        var failUsage = false
        var testFails: ErrorCode? = null
        val updateFails = mutableMapOf<String, ErrorCode>()
        val tests = mutableListOf<String?>()
        val updates = mutableListOf<String>()

        override suspend fun usage(): Result<Usage> {
            usageReads++
            return if (failUsage) Result.failure(IllegalStateException("read failed")) else engine.usage()
        }

        override suspend fun testLatency(groupId: String?): Result<Unit> {
            tests += groupId
            return testFails?.let { Result.failure(EngineException(it)) } ?: Result.success(Unit)
        }

        override suspend fun updateSubscription(id: String): Result<Subscription> {
            updates += id
            return updateFails[id]?.let { Result.failure(EngineException(it)) } ?: engine.updateSubscription(id)
        }
    }

    private fun TestScope.viewModel(engine: Engine) =
        StatsViewModel(engine, clock = { SAMPLE_NOW }, zone = { ZoneOffset.UTC }, pollMs = POLL_MS, work = UnconfinedTestDispatcher(testScheduler))

    @Test
    fun `shows today's usage once the first read answers and reads again while shown`() = runTest {
        val engine = Counting()
        val vm = viewModel(engine)
        assertTrue("before the first read", vm.uiState.value.today.quiet)
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        val expected = engine.usage().getOrThrow().today.down.take(11).sum()
        engine.usageReads = 0
        advanceTimeBy(1)
        assertEquals(expected, vm.uiState.value.today.downTotal)
        assertNotNull(vm.uiState.value.split)
        val reads = engine.usageReads
        advanceTimeBy(POLL_MS)
        assertTrue("read again after $POLL_MS ms", engine.usageReads > reads)
        job.cancel()
    }

    @Test
    fun `a failed read keeps the last answer`() = runTest {
        val engine = Counting()
        val vm = viewModel(engine)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        advanceTimeBy(1)
        assertFalse(vm.uiState.value.today.quiet)
        engine.failUsage = true
        advanceTimeBy(POLL_MS * 2)
        assertFalse("still the last day read", vm.uiState.value.today.quiet)
    }

    @Test
    fun `the tools test every node and update every subscription`() = runTest {
        val engine = Counting()
        val vm = viewModel(engine)
        vm.testAll()
        assertEquals(listOf<String?>(null), engine.tests)
        vm.updateAll()
        assertEquals(engine.subscriptions.value.map { it.id }, engine.updates)
    }

    @Test
    fun `a failed test shows until the test next succeeds`() = runTest {
        val engine = Counting().apply { testFails = ErrorCode.Busy }
        val vm = viewModel(engine)
        assertNull(vm.failure.value)
        vm.testAll()
        assertEquals(StatsFailure(StatsTool.TestAll, ErrorCode.Busy), vm.failure.value)
        vm.retry()
        assertEquals("重試 tests again", listOf<String?>(null, null), engine.tests)
        assertNotNull("still failing", vm.failure.value)
        engine.testFails = null
        vm.retry()
        assertNull("cleared by the next success", vm.failure.value)
    }

    @Test
    fun `a failed update names the first subscription that failed, and the others still update`() = runTest {
        val engine = Counting()
        val subscriptions = engine.subscriptions.value
        val failing = subscriptions.first()
        engine.updateFails[failing.id] = ErrorCode.FetchFailed
        val vm = viewModel(engine)
        vm.updateAll()
        assertEquals(subscriptions.map { it.id }, engine.updates)
        assertEquals(StatsFailure(StatsTool.UpdateAll, ErrorCode.FetchFailed, failing.name), vm.failure.value)
        engine.updateFails.clear()
        vm.retry()
        assertNull(vm.failure.value)
    }

    @Test
    fun `a tool that succeeds leaves the other tool's failure`() = runTest {
        val engine = Counting().apply { testFails = ErrorCode.Busy }
        val vm = viewModel(engine)
        vm.testAll()
        vm.updateAll()
        assertEquals(StatsTool.TestAll, vm.failure.value?.tool)
    }

    @Test
    fun `the chips' picks reach the page`() = runTest {
        val vm = viewModel(Counting())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        advanceTimeBy(1)
        assertNull(vm.uiState.value.period)
        vm.pickRange(RvStatsRange.Month)
        vm.pickSubscription("s2")
        advanceTimeBy(1)
        assertEquals(RvStatsRange.Month, vm.uiState.value.range)
        assertEquals(30, vm.uiState.value.period?.slots)
        assertEquals("s2", vm.uiState.value.subscriptionId)
        vm.pickRange(RvStatsRange.Today)
        vm.pickSubscription(null)
        advanceTimeBy(1)
        assertNull(vm.uiState.value.period)
        assertNull(vm.uiState.value.subscriptionId)
    }

    private companion object {
        const val POLL_MS = 5_000L
    }
}
