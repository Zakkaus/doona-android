package dev.doona.android.report

import dev.doona.android.engine.ErrorCode
import java.net.URLEncoder
import java.time.Instant

/** Failure context to include when building a ZIP and its issue link together. */
data class ReportFailure(val errorCode: ErrorCode, val command: String, val at: Instant)

/** Builds a GitHub draft URL, without making a request or attaching a file. */
object GitHubIssueLink {
    fun build(
        errorCode: ErrorCode,
        command: String,
        at: Instant,
        app: AppInfo,
        coreVersion: String?,
        events: List<AppEvent> = AppEvents.snapshot(),
        options: RedactionOptions = RedactionOptions(),
    ): String = build(ReportFailure(errorCode, command, at), app, coreVersion, events, ReportScrubber(options))

    internal fun build(
        failure: ReportFailure,
        app: AppInfo,
        coreVersion: String?,
        events: List<AppEvent>,
        scrubber: ReportScrubber,
    ): String {
        val code = failure.errorCode.name
        val name = scrubber.command(failure.command)
        val body = """
            ### What happened

                Error code: $code
                Command: $name
                Time (UTC): ${failure.at}

            ### Environment

                App: ${app.version}
                Core: ${scrubber.version(coreVersion)}
                Android: ${scrubber.metadata(app.android)} (SDK ${app.sdk})
                Device: ${scrubber.metadata(app.model)}

            Please attach the exported diagnostic ZIP.

            ### Recent actions
        """.trimIndent() + "\n\n"
        val prefix = "$ISSUE_URL?title=${encode("Doona: $name ($code)")}&body=${encode(body)}"
        val recent = ArrayDeque<String>()
        var length = prefix.length
        for (event in events.asReversed()) {
            val action = scrubber.action(event) ?: continue
            val line = encode(eventLine(event, action, scrubber))
            if (length + line.length >= URL_LIMIT) break
            recent.addFirst(line)
            length += line.length
            if (recent.size == EVENT_LIMIT) break
        }
        return buildString(length) {
            append(prefix)
            recent.forEach { append(it) }
        }
    }

    private fun eventLine(event: AppEvent, action: String, scrubber: ReportScrubber) = buildString {
        append("    ").append(Instant.ofEpochMilli(event.atEpochMs)).append(' ').append(event.level.name).append(' ').append(action)
        scrubber.domain(event)?.let { append(" domain=").append(it) }
        scrubber.appName(event)?.let { append(" app=").append(it) }
        append('\n')
    }

    private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8.name())

    private const val ISSUE_URL = "https://github.com/Zakkaus/doona-android/issues/new"
    private const val URL_LIMIT = 8_000
    private const val EVENT_LIMIT = 50
}
