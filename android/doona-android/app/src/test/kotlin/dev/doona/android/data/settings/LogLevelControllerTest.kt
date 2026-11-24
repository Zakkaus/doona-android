package dev.doona.android.data.settings

import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.LogLevel
import dev.doona.android.engine.fake.FakeEngine
import dev.doona.android.report.AppEvents
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
    fun `a failed reset records its code and retries once`() = runTest {
        for (refusals in 1..2) {
            AppEvents.clear()
            var resets = 0
            val engine = FakeEngine()
            val levels = LogLevelController({ level ->
                if (level == LogLevel.Info && ++resets <= refusals) Result.failure(EngineException(ErrorCode.Busy)) else engine.setLogLevel(level)
            }, backgroundScope) { testScheduler.currentTime }
            levels.set(LogLevel.Debug).getOrThrow()
            advanceTimeBy(3_600_000)
            runCurrent()
            assertEquals(LogLevel.Debug, engine.diagnostics().getOrThrow().level)
            advanceTimeBy(1_000)
            runCurrent()
            advanceTimeBy(10_000)
            runCurrent()

            assertEquals(2, resets)
            assertEquals(if (refusals == 1) LogLevel.Info else LogLevel.Debug, engine.diagnostics().getOrThrow().level)
            assertEquals("command=setLogLevel phase=reset outcome=failed code=Busy", AppEvents.snapshot().single().text)
            if (refusals == 2) {
                levels.expireIfDue()?.join()
                assertEquals(LogLevel.Info, engine.diagnostics().getOrThrow().level)
                assertEquals(3, resets)
            }
        }
    }

    private fun TestScope.levels(engine: FakeEngine) = LogLevelController(engine::setLogLevel, backgroundScope) { testScheduler.currentTime }
}
