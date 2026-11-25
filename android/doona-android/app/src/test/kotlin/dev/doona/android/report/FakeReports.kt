package dev.doona.android.report

import android.content.Intent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A [ReportExporter] over a real [ReportExport] with no share sheet: [export] writes the zip as the app's does and hands
 * it over with an empty intent. It records the [options] of each export; with [fail] set an export gives nothing, as one
 * whose zip or token could not be written; with [gate] set an export waits for it.
 */
internal class FakeReports(private val reports: ReportExport) : ReportExporter {
    val options = mutableListOf<RedactionOptions>()
    var fail = false
    var gate: CompletableDeferred<Unit>? = null

    override val crashOffered = MutableStateFlow(false)

    override fun crashShown() = Unit

    override fun dismissCrash() {
        crashOffered.value = false
    }

    override suspend fun export(options: RedactionOptions, failure: ReportFailure?): ReportShare? {
        this.options += options
        gate?.await()
        if (fail) return null
        val zip = reports.build(options, failure) ?: return null
        return ReportShare(Intent(), zip, reports)
    }
}
