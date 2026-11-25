package dev.doona.android.feature.report

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.engine.CoreDiagnostics
import dev.doona.android.engine.CrashFile
import dev.doona.android.engine.LogLevel
import dev.doona.android.engine.RulePage
import dev.doona.android.report.ExportFixture
import dev.doona.android.report.FakeReports
import dev.doona.android.report.RedactionOptions
import dev.doona.android.report.ReportExport
import dev.doona.android.report.ReportShare
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

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

    private suspend fun TestScope.page(fail: Boolean = false, facts: ReportFacts = this@ReportViewModelTest.facts): Pair<ReportViewModel, FakeReports> {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val engine = fixtureEngine()
        val reports = FakeReports(export(engine, scope = backgroundScope)).also { it.fail = fail }
        val vm = ReportViewModel(engine, reports, facts)
        backgroundScope.launch { vm.uiState.collect {} }
        return vm to reports
    }

    private fun zips(): List<File> = outDir.listFiles().orEmpty().filter { it.name.endsWith(".zip") }

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

    @Test
    fun `a share sheet's return keeps the ready report, to share again`() = runTest {
        val (vm, reports) = page()
        advanceUntilIdle()
        val share = checkNotNull(vm.uiState.value.share)
        val first = zips().single()

        vm.shareReturned(share)
        advanceTimeBy(ReportExport.SHARE_GRACE_MS + 1)
        runCurrent()
        assertEquals("not written again", 1, reports.options.size)
        assertSame(share, vm.uiState.value.share)
        assertEquals(RvReportBuild.Ready(RvRedaction()), vm.uiState.value.build)
        assertTrue("kept to share again", first.exists())
        vm.shareReturned(share)
        assertTrue(first.exists())
    }

    @Test
    fun `each switch shows or hides only its own part`() {
        val shown = RvRedaction(nodes = false, ip = false, domains = false, apps = false).options()
        assertEquals(RedactionOptions(showNodeEndpoints = true, showOwnAddresses = true, showVisitedDomains = true, showAppNames = true), shown)
        assertEquals(shown.copy(showNodeEndpoints = false), RvRedaction(nodes = true, ip = false, domains = false, apps = false).options())
        assertEquals(shown.copy(showOwnAddresses = false), RvRedaction(nodes = false, ip = true, domains = false, apps = false).options())
        assertEquals(shown.copy(showVisitedDomains = false), RvRedaction(nodes = false, ip = false, domains = true, apps = false).options())
        assertEquals(shown.copy(showAppNames = false), RvRedaction(nodes = false, ip = false, domains = false, apps = true).options())
    }

    @Test
    fun `share opens the ready report while its zip is there`() = runTest {
        val (vm, reports) = page()
        advanceUntilIdle()
        val share = checkNotNull(vm.uiState.value.share)
        val opened = mutableListOf<ReportShare>()

        vm.share { opened += it }
        advanceUntilIdle()
        assertEquals(listOf(share), opened)
        assertEquals("not written again", 1, reports.options.size)
    }

    @Test
    fun `a ready report whose zip is gone is written again, not shared`() = runTest {
        val (vm, reports) = page()
        advanceUntilIdle()
        val share = checkNotNull(vm.uiState.value.share)
        vm.shareReturned(share)
        assertTrue(zips().single().delete())
        val opened = mutableListOf<ReportShare>()

        vm.share { opened += it }
        advanceUntilIdle()
        assertEquals("nothing shared", emptyList<ReportShare>(), opened)
        assertEquals("written again with the same switches", listOf(reports.options[0], reports.options[0]), reports.options)
        val again = checkNotNull(vm.uiState.value.share)
        assertTrue(again !== share)
        assertEquals(RvReportBuild.Ready(RvRedaction()), vm.uiState.value.build)
        assertTrue(zips().single().exists())
        vm.share { opened += it }
        advanceUntilIdle()
        assertEquals(listOf(again), opened)
    }

    @Test
    fun `a shared report a switch replaces keeps its zip for the grace`() = runTest {
        val (vm, reports) = page()
        advanceUntilIdle()
        val share = checkNotNull(vm.uiState.value.share)
        val first = zips().single()
        vm.shareReturned(share)

        vm.setRedaction(vm.uiState.value.redaction.copy(apps = true))
        runCurrent()
        assertEquals(2, reports.options.size)
        assertTrue("kept while the picked app reads it", first.exists())
        advanceTimeBy(ReportExport.SHARE_GRACE_MS + 1)
        runCurrent()
        assertFalse(first.exists())
    }

    @Test
    fun `a link no app opens shows on the page until the report is shared or written again`() = runTest {
        val (vm, _) = page()
        advanceUntilIdle()
        val share = checkNotNull(vm.uiState.value.share)
        vm.linkFailed()
        assertTrue(vm.uiState.value.linkFailed)
        vm.shareReturned(share)
        assertFalse("shared instead", vm.uiState.value.linkFailed)

        vm.linkFailed()
        vm.setRedaction(vm.uiState.value.redaction.copy(apps = true))
        assertFalse("a new report has its own link", vm.uiState.value.linkFailed)
    }

    @Test
    fun `every report links to GitHub's new-issue form with the app and the phone`() = runTest {
        val (vm, _) = page()
        advanceUntilIdle()
        val link = checkNotNull(vm.uiState.value.share).issueLink
        assertTrue(link, link.startsWith("https://github.com/Zakkaus/doona-android/issues/new?template=bug_report.yml&version="))
        assertTrue(link, "&device=" in link)
    }

    @Test
    fun `a share sheet that could not open keeps the written report ready to share`() = runTest {
        val (vm, reports) = page()
        advanceUntilIdle()
        val share = checkNotNull(vm.uiState.value.share)
        val ready = vm.uiState.value.build
        val zip = zips().single()

        vm.shareFailed(share)
        runCurrent()
        assertTrue("the export did not fail", ready is RvReportBuild.Ready)
        assertEquals(ready, vm.uiState.value.build)
        assertSame(share, vm.uiState.value.share)
        assertTrue(zip.exists())
        assertEquals("nothing is written again", 1, reports.options.size)
    }

    @Test
    fun `the page going away drops the report nobody shared`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val engine = fixtureEngine()
        val reports = FakeReports(export(engine, scope = backgroundScope))
        val store = ViewModelStore()
        val vm = ViewModelProvider(store, ReportViewModel.factory(engine, reports, facts))[ReportViewModel::class.java]
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.share)
        val zip = zips().single()

        store.clear()
        runCurrent()
        assertFalse(zip.exists())
    }

    @Test
    fun `a log that cannot be read counts 0 events, and the page still opens`() = runTest {
        val broken = object : ReportFacts {
            override val app = "0.1"
            override val device = "Pixel 9 · Android 16"

            override suspend fun events(): Int = error("log unreadable")
        }
        val (vm, _) = page(facts = broken)
        advanceUntilIdle()
        assertEquals(0, vm.uiState.value.preview.events)
        assertEquals(3, vm.uiState.value.preview.nodes)
        assertEquals(RvReportBuild.Ready(RvRedaction()), vm.uiState.value.build)
    }

    @Test
    fun `the preview counts what was read, and 0 for each part that was not`() = runTest {
        val catalog = checkNotNull(fixtureEngine().catalog.value)
        val diagnostics = CoreDiagnostics(LogLevel.Info, listOf(CrashFile("1.txt", 1), CrashFile("2.txt", 1)), crashMark = 0, json = "{}")
        val read = rvReportPreview("0.1", "phone", catalog, emptyList(), RulePage(1, total = 14, rows = emptyList()), events = 5, diagnostics = diagnostics)
        assertEquals(RvReportPreview("0.1", "phone", nodes = 3, subscriptions = 0, rules = 14, events = 5, crashes = 2), read)
        val none = rvReportPreview("0.1", "phone", catalog = null, subscriptions = emptyList(), rules = null, events = null, diagnostics = null)
        assertEquals(RvReportPreview("0.1", "phone", nodes = 0, subscriptions = 0, rules = 0, events = 0, crashes = 0), none)
    }
}
