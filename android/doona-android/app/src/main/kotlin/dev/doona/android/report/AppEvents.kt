package dev.doona.android.report

import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.FailureReason

internal const val ACTION_COMMANDS = "start|stop|select|testLatency|addSubscription|updateSubscription|removeSubscription|setSubscriptionUserAgent|setMode|setRuleSource|setLogLevel|clearCrashes|export"
internal val ACTION_CODES = ErrorCode.entries.map { it.name } + FailureReason.entries.map { it.name } + listOf("ok", "IOException")
private val codes = ACTION_CODES.joinToString("|")

// Allow values, not just characters: even a single-label host or token can be an ASCII identifier.
internal val ACTION_TEXT = "command=(?:$ACTION_COMMANDS) phase=(?:dispatch|complete|deadline|write|reset) " +
    "outcome=(?:ok|failed|superseded|partial) code=(?:$codes)(?: elapsedMs=\\d{1,19})?"
internal val actionText = Regex(ACTION_TEXT)

/** How severe an app event is. */
enum class AppEventLevel { Info, Warn }

/** One event of the app: time, level, and a fixed-format diagnostic message. */
data class AppEvent(val atEpochMs: Long, val level: AppEventLevel, val text: String)

/**
 * The app's last [CAPACITY] events, oldest first, for the diagnostic report. Process-wide, written from any thread. Its
 * writers hold to ARCH D21 (`VpnLog`): no URL, node address, credential or DNS name, and no exception message.
 */
object AppEvents {
    const val CAPACITY = 200

    /** The longest text kept: an event is a line, not a document. */
    private const val MAX_TEXT = 200

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

    /** The events kept now, oldest first. */
    fun snapshot(): List<AppEvent> = synchronized(lock) { ring.toList() }

    /** Only actions may cross a process boundary in crash files. */
    internal fun breadcrumbs(limit: Int): List<AppEvent> = synchronized(lock) {
        val events = ArrayList<AppEvent>(limit)
        for (index in ring.lastIndex downTo 0) {
            val event = ring[index]
            if (!actionText.matches(event.text)) continue
            events.add(event)
            if (events.size == limit) break
        }
        events.reverse()
        events
    }

    /** Drops every event; for tests, which share the process. */
    internal fun clear() = synchronized(lock) { ring.clear() }
}
