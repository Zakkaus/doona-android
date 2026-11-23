package dev.doona.android.report.fake

import dev.doona.android.report.ExportState
import dev.doona.android.report.Reports
import kotlinx.coroutines.flow.MutableStateFlow

/** Reports that only show a state, for previews and screen tests: [startExport] and the dismissals change it as the real one would. */
class FakeReports(export: ExportState = ExportState.Idle, crashOffered: Boolean = false) : Reports {
    override val export = MutableStateFlow(export)
    override val crashOffered = MutableStateFlow(crashOffered)

    /** How many exports were asked for. */
    var exports = 0
        private set

    override fun startExport() {
        exports++
        crashOffered.value = false
        export.value = ExportState.Building
    }

    override fun dismissFailure() {
        if (export.value == ExportState.Failed) export.value = ExportState.Idle
    }

    override fun dismissCrash() {
        crashOffered.value = false
    }
}
