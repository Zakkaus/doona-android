package dev.doona.android.feature.config

import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.fake.RvSampleConfig
import dev.doona.android.honk.PolicyHonkFixtures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 版本紀錄, a revision's page and 設定檔 over honk's configuration db and accepted files. */
@OptIn(ExperimentalCoroutinesApi::class)
class ConfigHistoryViewModelTest {
    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val honk = PolicyHonkFixtures.configTree()

    @Test
    fun theHistoryIsHonksRevisionsNewestFirstWithTheirOrigins() {
        val history = checkNotNull(ConfigHistoryViewModel(honk, now = { RvSampleConfig.NOW }, reads = Dispatchers.Unconfined).uiState.value.value)
        assertEquals(RvSampleConfig.HEAD, history.headRev)
        assertEquals((RvSampleConfig.HEAD downTo 1).toList(), history.revisions.map { it.rev })
        assertEquals(RvSampleConfig.history.map { it.appliedAtEpochMs }, history.revisions.map { it.appliedAtEpochMs })
        assertEquals(RvRevisionOrigin.Restore, history.revisions[1].origin)
        assertEquals(RvRevisionOrigin.Import, history.revisions.last().origin)
    }

    @Test
    fun anExportHandsHonksInlinedFilesToThePicker() {
        val vm = ConfigHistoryViewModel(honk, reads = Dispatchers.Unconfined)
        vm.export()
        assertEquals(honk.call("GET", "/api/v1/x-honk/config/export", null, emptyMap()).body, vm.exported.value)
        assertNull(vm.exportNotice.value)
        vm.exportHandled()
        assertNull(vm.exported.value)
    }

    @Test
    fun aRevisionShowsNoDiffSinceHonkKeepsNoTextAndRestoreActivatesIt() = runTest {
        val vm = ConfigRevisionViewModel(honk, 8, reads = UnconfinedTestDispatcher(testScheduler))
        assertEquals(RvRevisionOrigin.Restore, checkNotNull(vm.uiState.value.value).revision.origin)
        assertEquals(emptyList<String>(), checkNotNull(vm.uiState.value.value).files.map { it.source })
        vm.restore()
        advanceUntilIdle()
        assertTrue(vm.restored.value)
        assertTrue(honk.calls.any { it.method == "POST" && it.path == "/api/v1/x-honk/config/revisions/8/activate" })
    }

    @Test
    fun aRevisionHonkNoLongerHoldsIsNotFound() {
        assertEquals(ErrorCode.NotFound, ConfigRevisionViewModel(honk, 42, reads = Dispatchers.Unconfined).uiState.value.failure)
    }

    @Test
    fun theOverviewListsHonksFilesUnderTheEntryAndSkipsNothing() {
        val state = checkNotNull(ConfigOverviewViewModel(honk, now = { RvSampleConfig.NOW }, reads = Dispatchers.Unconfined).uiState.value.value)
        assertEquals(RvSampleConfig.HEAD, state.rev)
        assertEquals(listOf("config.dae", "nodes.dae", "nodes/office.dae", "rules/cn.dae", "rules/lan.dae", "dns.dae"), state.files.map { it.path })
        assertEquals(listOf(null) + List(5) { "config.dae" }, state.files.map { it.parent })
        assertEquals(RvSampleConfig.history.first().appliedAtEpochMs, state.appliedAtEpochMs)
        assertEquals(emptyList<RvSkipped>(), state.skipped)
    }
}
