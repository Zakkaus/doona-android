package dev.doona.android.feature.report

import android.content.Context
import dev.doona.android.report.AppEvents
import dev.doona.android.report.AppInfo

/**
 * The app's [ReportFacts]: its version name and the phone as the report's `app` block has them ([AppInfo]), the phone
 * written as the preview names it, `Pixel 8 · Android 15`; and the events the app's log holds now.
 */
internal class AppReportFacts(context: Context) : ReportFacts {
    private val info = AppInfo.of(context)

    override val app: String = info.version

    override val device: String = "${info.model} · Android ${info.android}"

    override suspend fun events(): Int = AppEvents.reportSnapshot().size
}
