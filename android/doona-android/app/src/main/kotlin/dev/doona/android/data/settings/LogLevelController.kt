package dev.doona.android.data.settings

import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.feature.settings.RvLogLevel
import dev.doona.android.report.AppEvents
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

/** The process-local level honk logs at. A fresh honk starts at Info; no preference is read or saved. */
class LogLevelController(
    private val applyLevel: suspend (RvLogLevel) -> Result<Unit>,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
) {
    private val edits = Mutex()
    private var expiry: Job? = null
    private var resetStarted = false // Guarded by edits; prevents another reset while the retry is pending.
    private val current = MutableStateFlow(RvLogLevel.Info)
    val level: StateFlow<RvLogLevel> = current.asStateFlow()

    private val deadline = MutableStateFlow<Long?>(null)

    /** In the injected monotonic clock's milliseconds; null when no Debug capture is active. */
    val debugDeadlineMs: StateFlow<Long?> = deadline.asStateFlow()

    fun remainingMs(): Long = deadline.value?.let { (it - clock()).coerceAtLeast(0) } ?: 0

    // The core can commit even if the caller leaves; the process scope then publishes the result and owns the timer.
    suspend fun set(level: RvLogLevel): Result<Unit> = scope.async {
        edits.withLock {
            applyLevel(level).onSuccess {
                expiry?.cancel()
                resetStarted = false
                deadline.value = if (level == RvLogLevel.Debug) clock() + DEBUG_CAPTURE_MS else null
                current.value = level
                expiry = if (level == RvLogLevel.Debug) {
                    scope.launch {
                        delay(remainingMs())
                        expireIfDue()
                    }
                } else {
                    null
                }
            }
        }
    }.await()

    /** Ends Debug without changing a manually selected Error, Warn or Info level. */
    internal fun endCapture(): Job = scope.launch {
        edits.withLock { reset() }
    }

    /** Handler delays use uptime, so an interaction also checks the elapsed clock after deep sleep. */
    internal fun expireIfDue(): Job? {
        if (!due()) return null
        return scope.launch { edits.withLock { if (due()) reset() } }
    }

    private fun due(): Boolean = deadline.value?.let { clock() >= it } == true

    private suspend fun reset() {
        if (current.value != RvLogLevel.Debug || resetStarted) return
        resetStarted = true
        expiry?.cancel()
        expiry = null
        applyReset().onFailure { failure ->
            val code = (failure as? EngineException)?.code ?: ErrorCode.Internal
            AppEvents.action("setLogLevel", "reset", "failed", code.name)
            expiry = scope.launch {
                delay(RESET_RETRY_MS)
                edits.withLock {
                    expiry = null
                    applyReset().onFailure { resetStarted = false }
                }
            }
        }
    }

    private suspend fun applyReset(): Result<Unit> = applyLevel(RvLogLevel.Info).onSuccess {
        deadline.value = null
        current.value = RvLogLevel.Info
    }

    companion object {
        private const val DEBUG_CAPTURE_MS = 60 * 60 * 1_000L
        private const val RESET_RETRY_MS = 1_000L
    }
}
