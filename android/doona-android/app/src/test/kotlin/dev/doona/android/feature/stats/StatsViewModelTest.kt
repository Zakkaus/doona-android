package dev.doona.android.feature.stats

import dev.doona.android.engine.ErrorCode
import dev.doona.android.vpn.ActivityHonkFixtures
import dev.doona.android.vpn.ActivityHonkFixtures.empty
import dev.doona.android.vpn.ActivityHonkFixtures.refusingProbes
import dev.doona.android.vpn.FakeHonk
import dev.doona.android.vpn.HonkExamples
import dev.doona.android.vpn.HonkFixtures
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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class StatsViewModelTest {
    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** 統計 over [honk], its profile read once and its page collected, as the route shows it. */
    private fun TestScope.shown(honk: FakeHonk = ActivityHonkFixtures.sample()): StatsViewModel {
        val work = UnconfinedTestDispatcher(testScheduler)
        val vm = StatsViewModel(honk, clock = { SAMPLE_NOW }, zone = { ZoneOffset.UTC }, work = work, io = work)
        vm.load()
        backgroundScope.launch(work) { vm.uiState.collect {} }
        return vm
    }

    private fun FakeHonk.reads(path: String) = calls.count { it.method == "GET" && it.path == path }

    private fun FakeHonk.posts(path: String) = calls.count { it.method == "POST" && it.path == path }

    @Test
    fun `shows honk's profile once read, and reads nothing more while shown`() = runTest {
        val honk = ActivityHonkFixtures.sample()
        val vm = shown(honk)
        assertEquals(2, vm.uiState.value.subscriptions.size)
        assertEquals(48, vm.uiState.value.nodes.size)
        advanceTimeBy(60_000)
        assertEquals(1, honk.reads("/api/v1/providers"))
    }

    @Test
    fun `a failed read keeps the last answer`() = runTest {
        val honk = ActivityHonkFixtures.sample()
        val vm = shown(honk)
        honk.on("GET", "/api/v1/providers", UNAVAILABLE, HonkExamples.UNAVAILABLE)
        vm.load()
        assertEquals(2, honk.reads("/api/v1/providers"))
        assertEquals(2, vm.uiState.value.subscriptions.size)
    }

    @Test
    fun `a refused test shows until the test next succeeds, which probes every group and reads the profile again`() = runTest {
        val honk = ActivityHonkFixtures.sample().refusingProbes()
        val vm = shown(honk)
        vm.testAll()
        assertEquals(StatsFailure(StatsTool.TestAll, ErrorCode.Busy), vm.failure.value)
        honk.on("POST", ActivityHonkFixtures.PROBES_PATH, ACCEPTED, HonkFixtures.body("probes/queued"))
        honk.on("GET", "/api/v1/operations/op-01HZX4K8W9", OK, HonkFixtures.body("operations/probe-complete"))
        val probes = honk.posts(ActivityHonkFixtures.PROBES_PATH)
        vm.retry()
        // honk's operations are polled a second apart, one group after another.
        advanceTimeBy(8 * 1_000L + 1)
        assertNull(vm.failure.value)
        assertEquals(8, honk.posts(ActivityHonkFixtures.PROBES_PATH) - probes)
        assertEquals(3, honk.reads("/api/v1/providers"))
    }

    @Test
    fun `a failed update names the provider that failed, and the others still refresh`() = runTest {
        val honk = ActivityHonkFixtures.sample()
        honk.on("POST", "/api/v1/providers/s1/refresh", ACCEPTED, HonkFixtures.body("operations/provider-refresh-accepted"))
        honk.on("GET", "/api/v1/operations/op-refresh", OK, HonkFixtures.body("operations/provider-refresh-s1"))
        honk.on("POST", "/api/v1/providers/s2/refresh", CONFLICT, HonkExamples.UNAVAILABLE)
        val vm = shown(honk)
        vm.updateAll()
        advanceTimeBy(1_001)
        assertEquals(StatsFailure(StatsTool.UpdateAll, ErrorCode.Busy, "Lantern Net"), vm.failure.value)
        assertEquals(1, honk.reads("/api/v1/operations/op-refresh"))
        assertEquals(2, honk.reads("/api/v1/providers"))
    }

    @Test
    fun `a tool that succeeds leaves the other tool's failure`() = runTest {
        val honk = ActivityHonkFixtures.sample().refusingProbes()
        val vm = shown(honk)
        vm.testAll()
        // No provider to refresh: the update succeeds at once.
        honk.empty()
        vm.load()
        vm.updateAll()
        assertEquals(StatsTool.TestAll, vm.failure.value?.tool)
    }

    @Test
    fun `the chips' picks reach the page`() = runTest {
        val vm = shown()
        vm.pickRange(RvStatsRange.Week)
        vm.pickSubscription("s2")
        assertEquals(RvStatsRange.Week, vm.uiState.value.range)
        assertEquals("s2", vm.uiState.value.subscriptionId)
    }

    private companion object {
        const val OK = 200
        const val ACCEPTED = 202
        const val CONFLICT = 409
        const val UNAVAILABLE = 503
    }
}
