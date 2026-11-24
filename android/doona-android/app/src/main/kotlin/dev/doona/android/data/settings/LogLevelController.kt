package dev.doona.android.data.settings

import dev.doona.android.engine.LogLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The process-local core log level. A fresh core starts at Info; no preference is read or saved. */
class LogLevelController(
    private val applyLevel: suspend (LogLevel) -> Result<Unit>,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
) {
    private val edits = Mutex()
    private var expiry: Job? = null
    private val current = MutableStateFlow(LogLevel.Info)
    val level: StateFlow<LogLevel> = current.asStateFlow()

    private val deadline = MutableStateFlow<Long?>(null)

    /** In the injected monotonic clock's milliseconds; null when no Debug capture is active. */
    val debugDeadlineMs: StateFlow<Long?> = deadline.asStateFlow()

    fun remainingMs(): Long = deadline.value?.let { (it - clock()).coerceAtLeast(0) } ?: 0

    // The core can commit even if the caller leaves; the process scope then publishes the result and owns the timer.
    suspend fun set(level: LogLevel): Result<Unit> = scope.async {
        edits.withLock {
            applyLevel(level).onSuccess {
                expiry?.cancel()
                deadline.value = if (level == LogLevel.Debug) clock() + DEBUG_CAPTURE_MS else null
                current.value = level
                expiry = if (level == LogLevel.Debug) scope.launch { expire() } else null
            }
        }
    }.await()

    /** Ends Debug without changing a manually selected Error, Warn or Info level. */
    internal fun endCapture(): Job = scope.launch {
        edits.withLock {
            if (current.value == LogLevel.Debug) reset()
        }
    }

    private suspend fun expire() {
        delay(remainingMs())
        edits.withLock {
            expiry = null
            reset()
        }
    }

    private suspend fun reset() {
        if (applyLevel(LogLevel.Info).isSuccess) {
            deadline.value = null
            current.value = LogLevel.Info
            expiry?.cancel()
            expiry = null
        }
    }

    companion object {
        private const val DEBUG_CAPTURE_MS = 60 * 60 * 1_000L
    }
}
