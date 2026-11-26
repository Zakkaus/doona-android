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
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.feature.errorCode
import dev.doona.android.feature.policies.ruleCondition
import dev.doona.android.vpn.NativeApi
import dev.doona.android.vpn.honk.HonkConfig
import dev.doona.android.vpn.honk.HonkDnsRule
import dev.doona.android.vpn.honk.HonkDnsRules
import dev.doona.android.vpn.honk.HonkSource
import dev.doona.android.vpn.honk.NativeConfig
import dev.doona.android.vpn.honk.NativeRouting
import dev.doona.android.vpn.honk.daeCode
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
    val removeFailure: Pair<String, ErrorCode>? = null,
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
        viewModelScope.launch { load() }
    }

    /** Removes the statement keyed `<list>:<index>`, as [RvDnsRulesPage] names it. */
    fun remove(key: String) {
        val (list, index) = key.split(':').let { RvDnsList.valueOf(it[0]) to it[1].toInt() }
        val rule = read?.let { if (list == RvDnsList.Query) it.request else it.response }?.getOrNull(index) ?: return
        state.update { it.copy(removeFailure = null) }
        viewModelScope.launch {
            try {
                withContext(io) {
                    val (source, text) = dnsRuleRemoved(NativeConfig(api).config(), rule)
                    NativeConfig(api).replace(source, text)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                state.update { it.copy(removeFailure = key to error.errorCode()) }
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

/** `upstream` to `asis` is 原伺服器; a response's `upstream` or `requery` asks the server named again. */
private fun rvDnsRow(rule: HonkDnsRule, list: RvDnsList): RvDnsRow {
    val action = when {
        rule.action == REJECT -> RvDnsAction.Reject
        rule.action == ACCEPT -> RvDnsAction.Accept
        list == RvDnsList.Query && rule.upstream == ASIS -> RvDnsAction.AsIs
        else -> RvDnsAction.Upstream
    }
    return RvDnsRow(if (rule.isFallback) null else ruleCondition(rule.expression).trim(), action, rule.upstream?.takeIf { action == RvDnsAction.Upstream })
}

/**
 * [rule]'s file and its text without the rule's line. The line must still hold the statement honk reports (spaces aside),
 * else the file changed since honk loaded it: [ErrorCode.Busy].
 */
internal fun dnsRuleRemoved(config: HonkConfig, rule: HonkDnsRule): Pair<HonkSource, String> {
    val at = rule.source ?: throw EngineException(ErrorCode.InvalidArgument)
    val source = config.sources.firstOrNull { it.id == at.sourceId } ?: throw EngineException(ErrorCode.Busy)
    val lines = source.content.lines()
    val code = daeCode(lines.getOrNull(at.line - 1).orEmpty())
    if (code.filterNot(Char::isWhitespace) != rule.expression.filterNot(Char::isWhitespace)) throw EngineException(ErrorCode.Busy)
    return source to lines.filterIndexed { index, _ -> index != at.line - 1 }.joinToString("\n")
}

/** DNS 規則 in the app, always editable as its approved samples are. 新增規則 and a row's page come with the rule editor. */
@Composable
internal fun DnsRulesRoute(api: NativeApi, onBack: () -> Unit) {
    val viewModel: DnsRulesViewModel = viewModel(factory = DnsRulesViewModel.factory(api))
    val state = viewModel.uiState.collectAsStateWithLifecycle().value
    val lists = state.lists ?: return RvSettingsLoading()
    val removed = state.removeFailure?.let { (key, code) ->
        val (list, index) = key.split(':').let { RvDnsList.valueOf(it[0]) to it[1].toInt() }
        val title = (if (list == RvDnsList.Query) lists.query else lists.response).getOrNull(index)?.source.orEmpty()
        changeBanner(stringResource(R.string.rv_list_delete_failed_title, title), code) { viewModel.remove(key) }
    }
    RvDnsRulesPage(
        lists,
        onBack,
        edit = RvListEdit({}, viewModel::remove),
        banner = state.readFailure?.let { readBanner(it, viewModel::retry) } ?: removed,
    )
}

private const val REJECT = "reject"
private const val ACCEPT = "accept"
private const val ASIS = "asis"
