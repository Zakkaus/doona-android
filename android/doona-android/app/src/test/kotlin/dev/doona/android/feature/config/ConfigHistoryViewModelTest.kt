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
        val vm = ConfigHistoryViewModel(honk, now = { RvSampleConfig.NOW }, reads = Dispatchers.Unconfined)
        vm.retry()
        val history = checkNotNull(vm.uiState.value.value)
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
        assertNull(vm.exportNotice.value.value)
        val out = java.io.ByteArrayOutputStream()
        vm.exportHandled { out }
        assertEquals(honk.call("GET", "/api/v1/x-honk/config/export", null, emptyMap()).body, out.toString("UTF-8"))
        assertNull(vm.exported.value)
        assertEquals(RvExportNotice(false, emptyList()), vm.exportNotice.value.value)
        vm.export()
        vm.exportHandled(null)
        assertEquals(ConfigRead<RvExportNotice>(), vm.exportNotice.value)
    }

    @Test
    fun aProviderThatFailsOnCloseDoesNotCompleteTheExport() {
        val vm = ConfigHistoryViewModel(honk, reads = Dispatchers.Unconfined)
        vm.retry()
        vm.export()
        vm.exportHandled {
            object : java.io.ByteArrayOutputStream() {
                override fun close() = throw java.io.IOException()
            }
        }
        assertEquals(ErrorCode.WriteFailed, vm.exportNotice.value.failure)
        assertNull(vm.uiState.value.failure)
        assertEquals(RvSampleConfig.HEAD, vm.uiState.value.value?.headRev)
        assertNull(vm.exported.value)
    }

    @Test
    fun aRevisionWithoutSourceTextCanStillBeRestored() = runTest {
        val vm = ConfigRevisionViewModel(honk, 8, reads = UnconfinedTestDispatcher(testScheduler))
        val view = checkNotNull(vm.uiState.value.value)
        assertEquals(RvRevisionOrigin.Restore, view.revision.origin)
        assertNull(view.files)
        assertEquals(RvSampleConfig.HEAD, view.headRev)
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
        val vm = ConfigOverviewViewModel(honk, now = { RvSampleConfig.NOW }, reads = Dispatchers.Unconfined)
        vm.retry()
        val state = checkNotNull(vm.uiState.value.value)
        assertEquals(RvSampleConfig.HEAD, state.rev)
        assertEquals(listOf("config.dae", "nodes.dae", "nodes/office.dae", "rules/cn.dae", "rules/lan.dae", "dns.dae"), state.files.map { it.path })
        assertEquals(listOf(null) + List(5) { "config.dae" }, state.files.map { it.parent })
        assertEquals(RvSampleConfig.history.first().appliedAtEpochMs, state.appliedAtEpochMs)
        assertEquals(emptyList<RvSkipped>(), state.skipped)
    }

    @Test
    fun anUnreadablePickedFileLeavesTheConfigurationUntouched() {
        val vm = ConfigOverviewViewModel(honk, reads = Dispatchers.Unconfined)
        vm.import(null)
        assertEquals(RvConfigImport.Failed(null), vm.imported.value)
        assertTrue(honk.calls.none { it.method != "GET" })
    }
}
