package dev.doona.android.feature.report

import dev.doona.android.engine.Engine
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.LogLevel
import dev.doona.android.engine.fake.FakeEngine
import dev.doona.android.report.ExportState
import dev.doona.android.report.fake.FakeReports
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
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
class ReportViewModelTest {
    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `shows the core's level and sets the one chosen`() = runTest {
        val engine = FakeEngine().apply { logLevel = LogLevel.Warn }
        val vm = ReportViewModel(engine, FakeReports())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        assertEquals(LogLevel.Warn, vm.uiState.value.level)

        vm.onAction(ReportAction.SetLevel(LogLevel.Debug))
        assertEquals(LogLevel.Debug, engine.logLevel)
        assertEquals(LogLevel.Debug, vm.uiState.value.level)
    }

    @Test
    fun `a level the core refuses goes back and says why`() = runTest {
        val fake = FakeEngine()
        val engine = object : Engine by fake {
            override suspend fun setLogLevel(level: LogLevel) = Result.failure<Unit>(EngineException(ErrorCode.Busy))
        }
        val vm = ReportViewModel(engine, FakeReports())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

        vm.onAction(ReportAction.SetLevel(LogLevel.Debug))
        assertEquals(LogLevel.Info, vm.uiState.value.level)
        assertEquals(ErrorCode.Busy, vm.uiState.value.levelError)
        vm.onAction(ReportAction.DismissError)
        assertNull(vm.uiState.value.levelError)
    }

    @Test
    fun `export starts the report and shows it under way, and a failure until dismissed`() = runTest {
        val reports = FakeReports()
        val vm = ReportViewModel(FakeEngine(), reports)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

        vm.onAction(ReportAction.Export)
        assertEquals(1, reports.exports)
        assertTrue(vm.uiState.value.exporting)

        reports.export.value = ExportState.Failed
        assertTrue(vm.uiState.value.exportFailed)
        assertFalse(vm.uiState.value.exporting)
        vm.onAction(ReportAction.DismissError)
        assertFalse(vm.uiState.value.exportFailed)
    }
}
