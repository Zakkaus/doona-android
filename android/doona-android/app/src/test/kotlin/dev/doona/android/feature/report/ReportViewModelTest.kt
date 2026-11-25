package dev.doona.android.feature.report

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.report.ExportFixture
import dev.doona.android.report.FakeReports
import dev.doona.android.report.RedactionOptions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ReportViewModelTest : ExportFixture() {
    private val facts = object : ReportFacts {
        override val app = "0.1"
        override val device = "Pixel 9 · Android 16"

        override suspend fun events() = 7
    }

    @After
    fun resetDispatchers() = Dispatchers.resetMain()

    private suspend fun TestScope.page(fail: Boolean = false): Pair<ReportViewModel, FakeReports> {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val engine = fixtureEngine()
        val reports = FakeReports(export(engine, scope = backgroundScope)).also { it.fail = fail }
        val vm = ReportViewModel(engine, reports, facts)
        backgroundScope.launch { vm.uiState.collect {} }
        return vm to reports
    }

    @Test
    fun `the first report hides node addresses, own IPs and domains, and shows app names`() = runTest {
        val (vm, reports) = page()
        assertEquals(RvRedaction(nodes = true, ip = true, domains = true, apps = false), vm.uiState.value.redaction)
        advanceUntilIdle()
        val expected = RedactionOptions(showNodeEndpoints = false, showOwnAddresses = false, showVisitedDomains = false, showAppNames = true)
        assertEquals(listOf(expected), reports.options)
    }

    @Test
    fun `the page opens on a report being written, then offers it`() = runTest {
        val (vm, reports) = page()
        val gate = CompletableDeferred<Unit>().also { reports.gate = it }
        vm.retry()
        assertEquals(RvReportBuild.Building, vm.uiState.value.build)
        assertNull(vm.uiState.value.share)
        gate.complete(Unit)
        advanceUntilIdle()
        val state = vm.uiState.value
        assertEquals(RvReportBuild.Ready(RvRedaction()), state.build)
        assertNotNull(state.share)
        assertTrue(outDir.listFiles().orEmpty().any { it.name.endsWith(".zip") })
    }

    @Test
    fun `a switch writes the report again with what it now hides`() = runTest {
        val (vm, reports) = page()
        advanceUntilIdle()
        vm.setRedaction(RvRedaction(apps = true))
        assertEquals(RvReportBuild.Building, vm.uiState.value.build)
        advanceUntilIdle()
        assertEquals(RvReportBuild.Ready(RvRedaction(apps = true)), vm.uiState.value.build)
        assertFalse(reports.options.last().showAppNames)
        assertEquals(2, reports.options.size)
    }

    @Test
    fun `the same switches do not write it again`() = runTest {
        val (vm, reports) = page()
        advanceUntilIdle()
        vm.setRedaction(RvRedaction())
        advanceUntilIdle()
        assertEquals(1, reports.options.size)
    }

    @Test
    fun `a report that cannot be written fails, and retry writes it`() = runTest {
        val (vm, reports) = page(fail = true)
        advanceUntilIdle()
        assertEquals(RvReportBuild.Failed, vm.uiState.value.build)
        assertNull(vm.uiState.value.share)
        reports.fail = false
        vm.retry()
        advanceUntilIdle()
        assertEquals(RvReportBuild.Ready(RvRedaction()), vm.uiState.value.build)
    }

    @Test
    fun `the preview counts the catalog's nodes, the subscriptions and the app's events`() = runTest {
        val (vm, _) = page()
        advanceUntilIdle()
        val preview = vm.uiState.value.preview
        assertEquals("0.1", preview.app)
        assertEquals("Pixel 9 · Android 16", preview.device)
        assertEquals(3, preview.nodes)
        assertEquals(1, preview.subscriptions)
        assertEquals(7, preview.events)
    }
}
