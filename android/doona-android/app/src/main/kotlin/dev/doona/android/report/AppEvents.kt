package dev.doona.android.report

/** How severe an app event is. */
enum class AppEventLevel { Info, Warn }

/** One event of the app: when, how severe, and what happened, in command names, codes, counts and durations only. */
data class AppEvent(val atEpochMs: Long, val level: AppEventLevel, val text: String)

/**
 * The app's last [CAPACITY] events, oldest first, for the diagnostic report. Process-wide, written from any thread. Its
 * writers hold to ARCH D21 (`VpnLog`): no URL, node address, credential or DNS name, and no exception message.
 */
object AppEvents {
    const val CAPACITY = 200

    /** The longest text kept: an event is a line, not a document. */
    internal const val MAX_TEXT = 200

    private val lock = Any()
    private val ring = ArrayDeque<AppEvent>(CAPACITY)

    fun record(level: AppEventLevel, text: String, atEpochMs: Long = System.currentTimeMillis()): AppEvent {
        val event = AppEvent(atEpochMs, level, text.take(MAX_TEXT))
        synchronized(lock) {
            if (ring.size == CAPACITY) ring.removeFirst()
            ring.addLast(event)
        }
        return event
    }

    internal fun action(command: String, phase: String, outcome: String, code: String, elapsedMs: Long? = null): AppEvent {
        val level = if (outcome == "failed" || outcome == "partial") AppEventLevel.Warn else AppEventLevel.Info
        val elapsed = elapsedMs?.let { " elapsedMs=$it" }.orEmpty()
        return record(level, "command=$command phase=$phase outcome=$outcome code=$code$elapsed")
    }

    /** The newest [limit] events kept now, oldest first. */
    fun snapshot(limit: Int = CAPACITY): List<AppEvent> = synchronized(lock) { ring.takeLast(limit) }

    /** Drops every event; for tests, which share the process. */
    internal fun clear() = synchronized(lock) { ring.clear() }
}
