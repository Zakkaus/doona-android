package dev.doona.android.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RvListEdit
import dev.doona.android.designsystem.rv.RvNotice
import dev.doona.android.designsystem.rv.RvNoticeKind
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.feature.errorCode
import dev.doona.android.feature.messageRes
import dev.doona.android.feature.policies.RvReadOnReturn
import dev.doona.android.feature.policies.ruleCondition
import dev.doona.android.feature.policies.statementAt
import dev.doona.android.honk.HonkDnsRule
import dev.doona.android.honk.HonkDnsRules
import dev.doona.android.honk.NativeApi
import dev.doona.android.honk.NativeConfig
import dev.doona.android.honk.NativeRouting
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What DNS 規則 shows: honk's two DNS lists (null until read), why a removal did not land, why the read failed. */
internal data class DnsRulesUiState(
    val lists: RvDnsLists? = null,
    val removeFailure: Triple<String, ErrorCode, () -> Unit>? = null,
    val readFailure: ErrorCode? = null,
)

/**
 * DNS 規則 on honk's native API: `GET /dns/rules` read on [io] at start, after a removal and on 重試. A removal deletes
 * the statement's line from the file honk names, once the line still holds that statement; honk checks the file before
 * it takes it.
 */
internal class DnsRulesViewModel(private val api: NativeApi, private val io: CoroutineDispatcher = Dispatchers.IO) : ViewModel() {
    private val state = MutableStateFlow(DnsRulesUiState())
    private var read: HonkDnsRules? = null

    val uiState: StateFlow<DnsRulesUiState> = state.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    fun retry() {
        state.update { it.copy(removeFailure = null) }
        viewModelScope.launch { load() }
    }

    /** honk's id of the statement at [index] of [list], none for the list's fallback. */
    fun id(list: RvDnsList, index: Int): String? =
        read?.let { if (list == RvDnsList.Query) it.request else it.response }?.getOrNull(index)?.takeUnless { it.isFallback }?.id

    /** Removes the statement keyed `<list>:<index>`, as [RvDnsRulesPage] names it. */
    fun remove(key: String) {
        val (list, index) = key.split(':').let { RvDnsList.valueOf(it[0]) to it[1].toInt() }
        val dns = read ?: return
        val rule = (if (list == RvDnsList.Query) dns.request else dns.response).getOrNull(index) ?: return
        state.update { it.copy(removeFailure = null) }
        viewModelScope.launch {
            try {
                withContext(io) {
                    val config = NativeConfig(api).config()
                    if (config.generationId != dns.generationId) throw EngineException(ErrorCode.Busy)
                    val (source, text) = statementAt(config, rule.source, rule.expression).removed()
                    NativeConfig(api).replace(source, text)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                load()
                // The same statement still at its place is removed again; else the page offers to reload.
                val action: () -> Unit = if (id(list, index) == rule.id) ({ remove(key) }) else ::retry
                state.update { it.copy(removeFailure = Triple(ruleCondition(rule.expression).trim(), error.errorCode(), action)) }
                return@launch
            }
            load()
        }
    }

    private suspend fun load() {
        try {
            val dns = withContext(io) { NativeRouting(api).dnsRules() }
            read = dns
            state.update { it.copy(lists = rvDnsLists(dns), readFailure = null) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            state.update { it.copy(readFailure = error.errorCode()) }
        }
    }

    companion object {
        fun factory(api: NativeApi): ViewModelProvider.Factory = viewModelFactory {
            initializer { DnsRulesViewModel(api) }
        }
    }
}

/** honk's lists as the page shows them: each statement by its condition, the fallback last with none. */
internal fun rvDnsLists(dns: HonkDnsRules) = RvDnsLists(dns.request.map { rvDnsRow(it, RvDnsList.Query) }, dns.response.map { rvDnsRow(it, RvDnsList.Response) })

/** A request's `asis` action is 原伺服器; a response's `upstream` or `requery` asks the server named again. */
internal fun rvDnsRow(rule: HonkDnsRule, list: RvDnsList): RvDnsRow {
    val action = when {
        rule.action == REJECT -> RvDnsAction.Reject
        rule.action == ACCEPT -> RvDnsAction.Accept
        list == RvDnsList.Query && rule.action == ASIS -> RvDnsAction.AsIs
        else -> RvDnsAction.Upstream
    }
    return RvDnsRow(if (rule.isFallback) null else ruleCondition(rule.expression).trim(), action, rule.upstream?.takeIf { action == RvDnsAction.Upstream })
}

/**
 * DNS 規則 in the app, always editable as its approved samples are: a statement opens its page ([onOpen] with whether
 * it is a query's and honk's id; a fallback opens nothing), 新增規則 a new statement's ([onAdd]). Coming back reads again.
 */
@Composable
internal fun DnsRulesRoute(api: NativeApi, onBack: () -> Unit, onOpen: (Boolean, String) -> Unit = { _, _ -> }, onAdd: (Boolean) -> Unit = {}) {
    val viewModel: DnsRulesViewModel = viewModel(factory = DnsRulesViewModel.factory(api))
    val state = viewModel.uiState.collectAsStateWithLifecycle().value
    RvReadOnReturn(viewModel::retry)
    // A first read that failed has no lists: the page then holds its banner over empty lists.
    val lists = state.lists ?: state.readFailure?.let { RvDnsLists(emptyList(), emptyList()) } ?: return RvSettingsLoading()
    val removed = state.removeFailure?.let { (title, code, action) ->
        val label = if (action == viewModel::retry) R.string.rv_config_reload else R.string.rv_retry
        RvNotice(RvNoticeKind.Error, stringResource(R.string.rv_list_delete_failed_title, title), stringResource(code.messageRes()), stringResource(label), action, titleKeepWords = false)
    }
    RvDnsRulesPage(
        lists,
        onBack,
        onOpen = { list, i -> viewModel.id(list, i)?.let { onOpen(list == RvDnsList.Query, it) } },
        onAdd = { onAdd(it == RvDnsList.Query) },
        edit = RvListEdit({}, viewModel::remove),
        banner = state.readFailure?.let { readBanner(it, viewModel::retry) } ?: removed,
    )
}

private const val REJECT = "reject"
private const val ACCEPT = "accept"
private const val ASIS = "asis"
