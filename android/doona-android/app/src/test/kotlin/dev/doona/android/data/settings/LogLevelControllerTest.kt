package dev.doona.android.data.settings

import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.feature.settings.RvLogLevel
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
        val engine = Honk()
        val levels = levels(engine)
        val hour = 3_600_000L
        levels.set(RvLogLevel.Debug).getOrThrow()
        assertEquals(hour, levels.remainingMs())
        advanceTimeBy(hour / 2)
        levels.set(RvLogLevel.Debug).getOrThrow()
        advanceTimeBy(hour - 1)
        assertEquals(1L, levels.remainingMs())
        assertEquals(RvLogLevel.Debug, engine.level)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(RvLogLevel.Info, engine.level)
        assertEquals(RvLogLevel.Info, levels.level.value)
        assertEquals(0L, levels.remainingMs())
    }

    @Test
    fun `a failed reset records its code and retries once`() = runTest {
        for (refusals in 1..2) {
            AppEvents.clear()
            var resets = 0
            val engine = Honk()
            val levels = LogLevelController({ level ->
                if (level == RvLogLevel.Info && ++resets <= refusals) Result.failure(EngineException(ErrorCode.Busy)) else engine.set(level)
            }, backgroundScope) { testScheduler.currentTime }
            levels.set(RvLogLevel.Debug).getOrThrow()
            advanceTimeBy(3_600_000)
            runCurrent()
            assertEquals(RvLogLevel.Debug, engine.level)
            advanceTimeBy(1_000)
            runCurrent()
            advanceTimeBy(10_000)
            runCurrent()

            assertEquals(2, resets)
            assertEquals(if (refusals == 1) RvLogLevel.Info else RvLogLevel.Debug, engine.level)
            assertEquals("command=setLogLevel phase=reset outcome=failed code=Busy", AppEvents.snapshot().single { it.text.startsWith("command=") }.text)
            if (refusals == 2) {
                levels.expireIfDue()?.join()
                assertEquals(RvLogLevel.Info, engine.level)
                assertEquals(3, resets)
            }
        }
    }

    private fun TestScope.levels(engine: Honk) = LogLevelController(engine::set, backgroundScope) { testScheduler.currentTime }

    /** The level honk logs at, as the controller's patches leave it. */
    private class Honk {
        var level = RvLogLevel.Info

        fun set(level: RvLogLevel): Result<Unit> = Result.success(Unit).also { this.level = level }
    }
}
