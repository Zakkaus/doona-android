package dev.doona.android.report

import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.FailureReason
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.io.File

internal const val ACTION_COMMANDS = "start|stop|select|testLatency|addSubscription|updateSubscription|renameSubscription|removeSubscription|setSubscriptionUserAgent|setMode|setRuleSource|setDnsUpstreams|setLogLevel|clearCrashes|export"
internal val ACTION_CODES = ErrorCode.entries.map { it.name } + FailureReason.entries.map { it.name } + listOf("ok", "IOException")
private val codes = ACTION_CODES.joinToString("|")

// Allow values, not just characters: even a single-label host or token can be an ASCII identifier.
internal val ACTION_TEXT = "command=(?:$ACTION_COMMANDS) phase=(?:dispatch|complete|deadline|write|reset|retry) " +
    "outcome=(?:ok|failed|superseded|partial) code=(?:$codes)(?: elapsedMs=\\d{1,19})?"
internal val actionText = Regex(ACTION_TEXT)
internal const val SUBSCRIPTION_READY_TIMEOUT = "subscription-refresh: core readiness timed out"

// An event is a line, not a document.
private const val MAX_TEXT = 200

/** How severe an app event is. */
enum class AppEventLevel { Info, Warn }

/** Optional identifiers come from structured records, never from parsing log text. */
data class AppEvent(
    val atEpochMs: Long,
    val level: AppEventLevel,
    val text: String,
    val visitedDomain: String? = null,
    val appPackage: String? = null,
)

/** The app and VPN share this log for the lifetime of the process. */
internal object AppEvents : AppEventLog() {
    const val CAPACITY = 200
}

/**
 * The last [AppEvents.CAPACITY] events, oldest first, for the diagnostic report. Writable from any thread.
 * Event text follows ARCH D21 (`VpnLog`): no URL, node address, credential, DNS name or exception message.
 */
internal open class AppEventLog {
    private val installLock = Any()

    @Volatile
    private var ring = AppEventRing()

    internal fun install(io: CoroutineDispatcher = Dispatchers.IO, file: () -> File) = synchronized(installLock) {
        val previous = ring
        ring = AppEventRing(file, io, previous.startupEvents())
        previous.close()
    }

    fun record(
        level: AppEventLevel,
        text: String,
        atEpochMs: Long = System.currentTimeMillis(),
        visitedDomain: String? = null,
        appPackage: String? = null,
    ): AppEvent {
        val event = AppEvent(atEpochMs, level, text.take(MAX_TEXT), visitedDomain, appPackage)
        synchronized(installLock) { ring.add(event) }
        return event
    }

    internal fun action(command: String, phase: String, outcome: String, code: String, elapsedMs: Long? = null): AppEvent {
        val level = if (outcome == "failed" || outcome == "partial") AppEventLevel.Warn else AppEventLevel.Info
        val elapsed = elapsedMs?.let { " elapsedMs=$it" }.orEmpty()
        return record(level, "command=$command phase=$phase outcome=$outcome code=$code$elapsed")
    }

    /** The events kept in memory now, oldest first. Never waits for disk. */
    fun snapshot(): List<AppEvent> = ring.snapshot()

    internal suspend fun reportSnapshot(): List<AppEvent> = ring.loadedSnapshot()

    /** Only allowlisted actions from this process go into a new crash file. */
    internal fun breadcrumbs(limit: Int): List<AppEvent> = ring.breadcrumbs(limit)

    /** Drops every event; for tests, which share the process. */
    internal fun clear() = synchronized(installLock) {
        val previous = ring
        ring = AppEventRing()
        previous.close()
    }
}
