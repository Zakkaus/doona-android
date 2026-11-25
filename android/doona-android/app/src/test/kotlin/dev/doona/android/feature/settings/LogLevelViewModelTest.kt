package dev.doona.android.feature.settings

import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.LogLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LogLevelViewModelTest {
    /** The core's level; with [fails] set the core refuses each set. */
    private class FakeLevels(var fails: Boolean = false) : LogLevels {
        override val level = MutableStateFlow(LogLevel.Info)
        val sets = mutableListOf<LogLevel>()

        override suspend fun set(level: LogLevel): Result<Unit> {
            sets += level
            if (fails) return Result.failure(EngineException(ErrorCode.Internal))
            this.level.value = level
            return Result.success(Unit)
        }
    }

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `opens on the core's level, 資訊 on a fresh core`() = runTest {
        val vm = LogLevelViewModel(FakeLevels())
        assertEquals(RvLogLevel.Info, vm.uiState.value.level)
    }

    @Test
    fun `each choice sets the core's level and shows it`() = runTest {
        val levels = FakeLevels()
        val vm = LogLevelViewModel(levels)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        for (level in RvLogLevel.entries) {
            vm.select(level)
            assertEquals(level, vm.uiState.value.level)
        }
        assertEquals(listOf(LogLevel.Error, LogLevel.Warn, LogLevel.Info, LogLevel.Debug), levels.sets)
    }

    @Test
    fun `a set the core refuses leaves the level as it was and says so, and retry asks again`() = runTest {
        val levels = FakeLevels(fails = true)
        val vm = LogLevelViewModel(levels)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        assertNull(vm.uiState.value.refused)
        vm.select(RvLogLevel.Debug)
        assertEquals(LogLevelUiState(RvLogLevel.Info, refused = RvLogLevel.Debug), vm.uiState.value)

        levels.fails = false
        vm.retry()
        assertEquals(LogLevelUiState(RvLogLevel.Debug), vm.uiState.value)
        assertEquals(listOf(LogLevel.Debug, LogLevel.Debug), levels.sets)
    }

    @Test
    fun `除錯 returning to 資訊 by itself shows on the page`() = runTest {
        val levels = FakeLevels()
        val vm = LogLevelViewModel(levels)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        vm.select(RvLogLevel.Debug)
        levels.level.value = LogLevel.Info
        assertEquals(RvLogLevel.Info, vm.uiState.value.level)
    }

    @Test
    fun `the page's levels and the core's map one to one`() {
        for (level in LogLevel.entries) assertEquals(level, rvLogLevel(level).engineLevel())
    }
}
