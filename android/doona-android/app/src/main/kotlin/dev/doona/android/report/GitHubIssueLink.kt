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
        val what = """
            Error code: $code
            Command: $name
            Time (UTC): ${failure.at}
            Core: ${scrubber.version(coreVersion)}

            Recent actions:
        """.trimIndent() + "\n\n"
        val prefix = "${plain(app, scrubber)}&title=${encode("bug: $name ($code)")}&what=${encode(what)}"
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

    /**
     * The new-issue form with the app, Android and phone filled in and nothing about a failure: 回報問題's link, where the
     * user says what went wrong and attaches the report.
     */
    internal fun plain(app: AppInfo, scrubber: ReportScrubber): String {
        val device = "${scrubber.metadata(app.manufacturer)} ${scrubber.metadata(app.model)}"
        return "$ISSUE_URL?template=bug_report.yml&version=${encode("doona ${app.version}")}" +
            "&android=${encode("${scrubber.metadata(app.android)} (SDK ${app.sdk})")}&device=${encode(device)}"
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
