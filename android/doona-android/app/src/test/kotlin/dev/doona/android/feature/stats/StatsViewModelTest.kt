package dev.doona.android.feature.stats

import dev.doona.android.engine.Engine
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

    /** The sample engine, counting its reads and calls, with `usage()` failing while [failUsage] is set. */
    private class Counting(private val engine: Engine = rvSampleEngine()) : Engine by engine {
        var usageReads = 0
        var failUsage = false
        val tests = mutableListOf<String?>()
        val updates = mutableListOf<String>()

        override suspend fun usage(): Result<Usage> {
            usageReads++
            return if (failUsage) Result.failure(IllegalStateException("read failed")) else engine.usage()
        }

        override suspend fun testLatency(groupId: String?): Result<Unit> {
            tests += groupId
            return Result.success(Unit)
        }

        override suspend fun updateSubscription(id: String): Result<Subscription> {
            updates += id
            return engine.updateSubscription(id)
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

    private companion object {
        const val SAMPLE_NOW = 1_795_343_040_000L
        const val POLL_MS = 5_000L
    }
}
