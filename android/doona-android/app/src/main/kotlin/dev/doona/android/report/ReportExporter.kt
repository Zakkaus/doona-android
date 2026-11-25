package dev.doona.android.report

import android.content.Context
import android.content.Intent
import dev.doona.android.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * The diagnostic report, with no UI of its own, for a page to call: [export] writes the report and gives the share sheet
 * that sends it; [crashOffered] says when a crash waits to be reported. Nothing is sent unless the user picks an app in
 * the sheet, and a pick clears the crash files the zip holds.
 */
interface ReportExporter {
    /** True from a start that found a crash not offered before, until [dismissCrash] or [export]. */
    val crashOffered: StateFlow<Boolean>

    /** The crash offer reached the screen: it is not made again for the same crash, should the process end. */
    fun crashShown()

    fun dismissCrash()

    /**
     * Writes the report and gives its share sheet; null when the zip, its token or the sheet could not be made. Takes the
     * crash offer down. Reads the disk on its own dispatcher. If the caller leaves while the zip is being written,
     * a completed zip remains for the next sweep.
     */
    suspend fun export(options: RedactionOptions = RedactionOptions(), failure: ReportFailure? = null): ReportShare?
}

/**
 * A report ready to go: start [intent], the share sheet. Then call [returned] once the sheet returns, whatever the user
 * did there, or [notOpened] when it could not open, so the zip goes; a zip neither is called for goes at the next start.
 */
class ReportShare internal constructor(val intent: Intent, private val zip: ReportZip, private val reports: ReportExport) {
    val issueLink: String get() = zip.issueLink

    /** Whether the zip is still there to share; read on the export's dispatcher. */
    suspend fun present(): Boolean = reports.present(zip)

    fun returned() = reports.returned(zip)

    fun notOpened() = reports.notOpened(zip)
}

/** [ReportExporter] of the app: [reports] writes the zip; the share sheet is titled from [context]'s strings. */
class AppReportExporter(
    private val context: Context,
    private val reports: ReportExport,
    private val io: CoroutineDispatcher,
) : ReportExporter {
    override val crashOffered: StateFlow<Boolean> get() = reports.crashOffered

    override fun crashShown() = reports.crashShown()

    override fun dismissCrash() = reports.dismissCrash()

    override suspend fun export(options: RedactionOptions, failure: ReportFailure?): ReportShare? {
        val zip = reports.build(options, failure) ?: return null
        val title = context.getString(R.string.report_share_title)
        val intent = try {
            withContext(io) { reportShareIntent(context, zip.file, title, zip.token) }
        } catch (e: CancellationException) {
            reports.notOpened(zip)
            throw e
        } catch (_: IllegalArgumentException) {
            // The provider does not serve the file.
            reports.notOpened(zip)
            return null
        }
        return ReportShare(intent, zip, reports)
    }
}
