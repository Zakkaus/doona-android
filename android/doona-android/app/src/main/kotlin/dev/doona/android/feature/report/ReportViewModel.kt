package dev.doona.android.feature.report

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.doona.android.engine.Engine
import dev.doona.android.report.RedactionOptions
import dev.doona.android.report.ReportExporter
import dev.doona.android.report.ReportShare
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What the report page reads besides the engine: the app's version and the phone, as the preview names them, and how
 * many events the app's log holds. The app gives its own; a test gives fixed ones.
 */
internal interface ReportFacts {
    val app: String
    val device: String

    suspend fun events(): Int
}

/**
 * 回報問題: writes the report with the redaction the switches show as the page opens, again on each turn of a switch
 * and on 重試, and holds the written report ([ReportUiState.share]) for 在 GitHub 回報 and 分享檔案. A report written
 * for switches that no longer stand is not offered (the page shows it being written again); a newer write replaces it,
 * and the one it replaces goes, nothing having been shared ([ReportShare.notOpened]).
 */
internal class ReportViewModel(
    private val engine: Engine,
    private val reports: ReportExporter,
    private val facts: ReportFacts,
) : ViewModel() {
    private val state = MutableStateFlow(ReportUiState(preview = emptyPreview(facts)))
    private var building: Job? = null

    val uiState: StateFlow<ReportUiState> = state.asStateFlow()

    init {
        viewModelScope.launch { state.update { it.copy(preview = reportPreview(engine, facts)) } }
        build()
    }

    fun setRedaction(redaction: RvRedaction) {
        if (redaction == state.value.redaction) return
        state.update { it.copy(redaction = redaction) }
        build()
    }

    fun retry() = build()

    /** The share sheet for [share] returned, whatever the user did there: its zip goes after the export's grace. */
    fun shareReturned(share: ReportShare) {
        share.returned()
        if (state.value.share === share) state.update { it.copy(share = null) }
        build()
    }

    /** The share sheet for [share] could not open. */
    fun shareFailed(share: ReportShare) {
        share.notOpened()
        if (state.value.share === share) state.update { it.copy(share = null, build = RvReportBuild.Failed) }
    }

    private fun build() {
        building?.cancel()
        val redaction = state.value.redaction
        state.value.share?.notOpened()
        state.update { it.copy(build = RvReportBuild.Building, share = null) }
        building = viewModelScope.launch {
            val share = try {
                reports.export(redaction.options())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            state.update {
                if (share == null) it.copy(build = RvReportBuild.Failed) else it.copy(build = RvReportBuild.Ready(redaction), share = share)
            }
        }
    }

    override fun onCleared() {
        state.value.share?.notOpened()
    }

    companion object {
        fun factory(engine: Engine, reports: ReportExporter, facts: ReportFacts): ViewModelProvider.Factory = viewModelFactory {
            initializer { ReportViewModel(engine, reports, facts) }
        }
    }
}

/** The page's state: where the report stands, what the switches hide, the preview, and the written report. */
internal data class ReportUiState(
    val build: RvReportBuild = RvReportBuild.Building,
    val redaction: RvRedaction = RvRedaction(),
    val preview: RvReportPreview,
    val share: ReportShare? = null,
)

/** The export's options for what the switches hide: each switch on hides its part. */
internal fun RvRedaction.options() = RedactionOptions(
    showNodeEndpoints = !nodes,
    showOwnAddresses = !ip,
    showVisitedDomains = !domains,
    showAppNames = !apps,
)

/** The preview before the engine is read: the app and the phone, every count 0. */
private fun emptyPreview(facts: ReportFacts) = RvReportPreview(facts.app, facts.device, nodes = 0, subscriptions = 0, rules = 0, events = 0, crashes = 0)

/**
 * The preview of what a report holds, from the engine and the app's log as they stand ([rvReportPreview]). A read that
 * fails counts 0: the export reads them again and says what it could not read.
 */
internal suspend fun reportPreview(engine: Engine, facts: ReportFacts): RvReportPreview = rvReportPreview(
    app = facts.app,
    device = facts.device,
    catalog = engine.catalog.value,
    subscriptions = engine.subscriptions.value,
    rules = engine.rules(0, 1).getOrNull(),
    events = readEvents(facts),
    diagnostics = engine.diagnostics().getOrNull(),
)

/** The app's events, or null when the log could not be read. */
private suspend fun readEvents(facts: ReportFacts): Int? = try {
    facts.events()
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}
