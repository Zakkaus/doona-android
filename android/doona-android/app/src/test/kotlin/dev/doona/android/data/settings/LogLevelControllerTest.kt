package dev.doona.android.data.settings

import dev.doona.android.engine.LogLevel
import dev.doona.android.engine.fake.FakeEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LogLevelControllerTest {
    @Test
    fun `Debug expires one hour after the last selection`() = runTest {
        val engine = FakeEngine()
        val levels = levels(engine)
        val hour = 3_600_000L
        levels.set(LogLevel.Debug).getOrThrow()
        assertEquals(hour, levels.remainingMs())
        advanceTimeBy(hour / 2)
        levels.set(LogLevel.Debug).getOrThrow()
        advanceTimeBy(hour - 1)
        assertEquals(1L, levels.remainingMs())
        assertEquals(LogLevel.Debug, engine.diagnostics().getOrThrow().level)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(LogLevel.Info, engine.diagnostics().getOrThrow().level)
        assertEquals(LogLevel.Info, levels.level.value)
        assertEquals(0L, levels.remainingMs())
    }

    @Test
    fun `a new process starts at Info even if the last capture never ended`() = runTest {
        val firstEngine = FakeEngine()
        levels(firstEngine).set(LogLevel.Debug).getOrThrow()
        assertEquals(LogLevel.Debug, firstEngine.diagnostics().getOrThrow().level)

        val restartedEngine = FakeEngine()
        val restarted = levels(restartedEngine)
        assertEquals(LogLevel.Info, restarted.level.value)
        assertEquals(LogLevel.Info, restartedEngine.diagnostics().getOrThrow().level)
        assertEquals(0L, restarted.remainingMs())
    }

    private fun TestScope.levels(engine: FakeEngine) = LogLevelController(engine::setLogLevel, backgroundScope) { testScheduler.currentTime }
}
