package dev.doona.android.feature.report

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.doona.android.engine.Engine
import dev.doona.android.honk.NativeApi
import dev.doona.android.honk.NativeCatalog
import dev.doona.android.honk.ProviderKind
import dev.doona.android.honk.read
import dev.doona.android.report.RedactionOptions
import dev.doona.android.report.ReportExporter
import dev.doona.android.report.ReportShare
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

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
 * and the one it replaces goes after the share grace if its chooser opened, immediately otherwise. A missing zip is
 * written again rather than shared ([share]).
 */
internal class ReportViewModel(
    private val engine: Engine,
    private val api: NativeApi,
    private val reports: ReportExporter,
    private val facts: ReportFacts,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val state = MutableStateFlow(ReportUiState(preview = emptyPreview(facts)))
    private var building: Job? = null

    /** The chooser opened for this report; keep its zip for the grace period when released. */
    private var opened: ReportShare? = null

    val uiState: StateFlow<ReportUiState> = state.asStateFlow()

    init {
        viewModelScope.launch { state.update { it.copy(preview = reportPreview(engine, api, facts, io)) } }
        build()
    }

    fun setRedaction(redaction: RvRedaction) {
        if (redaction == state.value.redaction) return
        state.update { it.copy(redaction = redaction) }
        build()
    }

    fun retry() = build()

    /**
     * 分享檔案 on the ready report: [open] gets it while its zip is still on disk. One whose zip is gone (the system
     * cleared the app's cache while the page waited) is not offered: the report is written again, and the page shows it
     * being written.
     */
    fun share(open: (ReportShare) -> Unit) {
        val share = state.value.share ?: return
        viewModelScope.launch {
            val present = share.present()
            if (state.value.share !== share) return@launch
            if (present) open(share) else build()
        }
    }

    /** 在 GitHub 回報 could not open its link (no app on the phone opens it): the page says so and offers 分享檔案. */
    fun linkFailed() = state.update { it.copy(linkFailed = true) }

    /** The chooser opened. Keep the report ready and allow time for the recipient to read its zip after release. */
    fun shareOpened(share: ReportShare) {
        opened = share
        state.update { it.copy(linkFailed = false) }
    }

    /**
     * The share sheet for [share] could not open, so nothing was shared. The export did not fail: the report the page
     * offers stays ready, its zip with it, for another try or 在 GitHub 回報. One no longer offered goes now.
     */
    fun shareFailed(share: ReportShare) {
        if (state.value.share !== share) share.notOpened()
    }

    private fun build() {
        building?.cancel()
        val redaction = state.value.redaction
        release()
        state.update { it.copy(build = RvReportBuild.Building, share = null, linkFailed = false) }
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

    override fun onCleared() = release()

    /** Lets the offered report go: shared, its zip after the grace; never shared, at once. */
    private fun release() {
        val share = state.value.share ?: return
        if (share === opened) share.deleteAfterGrace() else share.notOpened()
        opened = null
    }

    companion object {
        fun factory(engine: Engine, api: NativeApi, reports: ReportExporter, facts: ReportFacts): ViewModelProvider.Factory = viewModelFactory {
            initializer { ReportViewModel(engine, api, reports, facts) }
        }
    }
}

/**
 * The page's state: where the report stands, what the switches hide, the preview, the written report, and whether
 * 在 GitHub 回報 could not open its link ([linkFailed], until the report is shared or written again).
 */
internal data class ReportUiState(
    val build: RvReportBuild = RvReportBuild.Building,
    val redaction: RvRedaction = RvRedaction(),
    val preview: RvReportPreview,
    val share: ReportShare? = null,
    val linkFailed: Boolean = false,
)

/** The export's options for what the switches hide: each switch on hides its part. */
internal fun RvRedaction.options() = RedactionOptions(
    showNodeEndpoints = !nodes,
    showOwnAddresses = !ip,
    showVisitedDomains = !domains,
    showAppNames = !apps,
)

/** Before the first read only the app and device are known. */
private fun emptyPreview(facts: ReportFacts) = RvReportPreview(facts.app, facts.device, nodes = null, subscriptions = null, rules = null, events = null, crashes = null)

/**
 * The preview of what a report holds, from honk ([api]: its nodes, subscriptions and rules), the core's crash listing
 * and the app's log as they stand ([rvReportPreview]). A ready report supplies its own log counts.
 */
internal suspend fun reportPreview(engine: Engine, api: NativeApi, facts: ReportFacts, io: CoroutineDispatcher): RvReportPreview {
    val catalog = NativeCatalog(api)
    val (nodes, subscriptions, rules) = withContext(io) {
        Triple(
            runCatching { catalog.nodes().size }.getOrNull(),
            runCatching { catalog.providers().count { it.kind == ProviderKind.Subscription } }.getOrNull(),
            runCatching { api.read("GET", "/api/v1/rules", HonkRuleCount.serializer()).rules.size }.getOrNull(),
        )
    }
    return rvReportPreview(
        app = facts.app,
        device = facts.device,
        nodes = nodes,
        subscriptions = subscriptions,
        rules = rules,
        events = readEvents(facts),
        diagnostics = engine.diagnostics().getOrNull(),
    )
}

/** honk's `GET /rules`, only to count its rows. */
@Serializable
private class HonkRuleCount(val rules: List<JsonElement>)

/** The app's events, or null when the log could not be read. */
private suspend fun readEvents(facts: ReportFacts): Int? = try {
    facts.events()
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}
