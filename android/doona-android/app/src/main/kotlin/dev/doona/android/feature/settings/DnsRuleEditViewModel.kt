package dev.doona.android.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RvDiffFile
import dev.doona.android.designsystem.rv.RvDiffKind
import dev.doona.android.designsystem.rv.RvDiffLine
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.feature.errorCode
import dev.doona.android.feature.model.RvDnsServer
import dev.doona.android.feature.model.RvMatchKind
import dev.doona.android.feature.policies.RvNativeRuleAt
import dev.doona.android.feature.policies.RvRuleDraft
import dev.doona.android.feature.policies.RvRuleFormFailed
import dev.doona.android.feature.policies.formMatch
import dev.doona.android.feature.policies.honkUpstreams
import dev.doona.android.feature.policies.matchText
import dev.doona.android.feature.policies.ruleCondition
import dev.doona.android.feature.policies.statementAt
import dev.doona.android.vpn.NativeApi
import dev.doona.android.vpn.honk.HonkConfig
import dev.doona.android.vpn.honk.HonkDnsRule
import dev.doona.android.vpn.honk.HonkDnsRules
import dev.doona.android.vpn.honk.HonkSource
import dev.doona.android.vpn.honk.NativeConfig
import dev.doona.android.vpn.honk.NativeRouting
import dev.doona.android.vpn.honk.daeBlock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What a DNS rule's page shows: the [draft] (null until read), the rule's place in its list and, for a match the form
 * cannot hold, its line ([at]), the config's DNS servers; why a write or the read failed; [done] once a write landed.
 */
@Immutable
internal data class DnsRuleEditUiState(
    val draft: RvDnsRuleDraft? = null,
    val at: RvNativeRuleAt? = null,
    val upstreams: List<RvDnsServer> = emptyList(),
    val writeFailure: ErrorCode? = null,
    val readFailure: ErrorCode? = null,
    val done: Boolean = false,
)

/**
 * A DNS rule's page (DNS 規則 → a statement, or 新增規則 when [ruleId] is null) of the [list] on honk's native API: honk's
 * DNS rules and config read on [io] at start and on 重試. The writes are the rule page's ([RuleEditViewModel]'s): a new
 * statement goes before its list's fallback, or last in the list's block when the fallback is not written.
 */
internal class DnsRuleEditViewModel(
    private val api: NativeApi,
    private val list: RvDnsList,
    private var ruleId: String?,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val state = MutableStateFlow(DnsRuleEditUiState())
    private var read: HonkDnsRules? = null

    val uiState: StateFlow<DnsRuleEditUiState> = state.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    fun retry() {
        viewModelScope.launch { load() }
    }

    fun change(draft: RvDnsRuleDraft) = state.update { it.copy(draft = draft) }

    fun apply() {
        val draft = state.value.draft ?: return
        write { config, dns ->
            val rule = rule(dns)
            if (rule == null) {
                val text = dnsRuleText(draft, null).let { if (draft.match.enabled) it else "# $it" }
                val fallback = statements(dns).lastOrNull { it.isFallback }?.source
                if (fallback != null) statementAt(config, fallback, null).before(text) else appended(config, list, text)
            } else {
                val line = statementAt(config, rule.source, rule.expression)
                val condition = state.value.at?.statement?.let { ruleCondition(line.code) }
                if (!draft.match.enabled) line.paused() else line.replaced(dnsRuleText(draft, condition))
            }
        }
    }

    fun delete() {
        if (ruleId == null) return state.update { it.copy(done = true) }
        write { config, dns ->
            val rule = rule(dns) ?: throw EngineException(ErrorCode.NotFound)
            statementAt(config, rule.source, rule.expression).removed()
        }
    }

    /** 上移 or 下移: the statement to 1-based place [index] in its list, the page then on it there. */
    fun move(index: Int) {
        val other = read?.let(::statements)?.filterNot { it.isFallback }?.getOrNull(index - 1) ?: return
        write(landed = { ruleId = other.id }) { config, dns ->
            val rule = rule(dns) ?: throw EngineException(ErrorCode.NotFound)
            statementAt(config, rule.source, rule.expression).swapped(statementAt(config, other.source, other.expression))
        }
    }

    private fun write(landed: (() -> Unit)? = null, edit: (HonkConfig, HonkDnsRules) -> Pair<HonkSource, String>) {
        val dns = read ?: return
        state.update { it.copy(writeFailure = null) }
        viewModelScope.launch {
            try {
                withContext(io) {
                    val (source, text) = edit(NativeConfig(api).config(), dns)
                    NativeConfig(api).replace(source, text)
                }
                if (landed == null) {
                    state.update { it.copy(done = true) }
                } else {
                    landed()
                    load()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                state.update { it.copy(writeFailure = error.errorCode()) }
            }
        }
    }

    private suspend fun load() {
        try {
            val (dns, config) = withContext(io) { NativeRouting(api).dnsRules() to NativeConfig(api).config() }
            read = dns
            val upstreams = honkUpstreams(config).map { RvDnsServer(it.name, it.address) }
            val rule = ruleId?.let { rule(dns) ?: throw EngineException(ErrorCode.NotFound) }
            val rules = statements(dns).filterNot { it.isFallback }
            state.update {
                it.copy(
                    draft = rule?.let(::draft) ?: RvDnsRuleDraft(list, RvRuleDraft(RvMatchKind.Suffix, "", ""), list.actions.first()),
                    at = rule?.let { r -> RvNativeRuleAt(rules.indexOf(r) + 1, rules.size, statement(r)) },
                    upstreams = upstreams,
                    readFailure = null,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            state.update { it.copy(readFailure = error.errorCode()) }
        }
    }

    private fun statements(dns: HonkDnsRules) = if (list == RvDnsList.Query) dns.request else dns.response

    private fun rule(dns: HonkDnsRules): HonkDnsRule? = statements(dns).firstOrNull { it.id == ruleId && !it.isFallback }

    /** [rule] as the form edits it (a match the form cannot hold keeps its text, for 刪除規則 to name). */
    private fun draft(rule: HonkDnsRule): RvDnsRuleDraft {
        val condition = ruleCondition(rule.expression)
        val (kind, value) = formMatch(condition, DOMAIN, ADDRESS) ?: (RvMatchKind.Suffix to condition)
        val row = rvDnsRow(rule, list)
        return RvDnsRuleDraft(list, RvRuleDraft(kind, value, ""), row.action, row.upstream)
    }

    private fun statement(rule: HonkDnsRule): RvDiffFile? = rule.source?.takeIf { formMatch(ruleCondition(rule.expression), DOMAIN, ADDRESS) == null }?.let {
        RvDiffFile(it.file, listOf(listOf(RvDiffLine(RvDiffKind.Same, it.line, rule.expression))))
    }

    companion object {
        fun factory(api: NativeApi, list: RvDnsList, ruleId: String?): ViewModelProvider.Factory = viewModelFactory {
            initializer { DnsRuleEditViewModel(api, list, ruleId) }
        }
    }
}

/** The statement's text: [condition] (a statement's own, or the form's match), the arrow, its action. */
private fun dnsRuleText(draft: RvDnsRuleDraft, condition: String?): String {
    val action = when (draft.action) {
        RvDnsAction.Reject -> "reject"
        RvDnsAction.AsIs -> "asis"
        RvDnsAction.Accept -> "accept"
        RvDnsAction.Upstream -> draft.upstream ?: throw EngineException(ErrorCode.InvalidArgument)
    }
    return "${condition ?: matchText(draft.match.kind, draft.match.value, DOMAIN, ADDRESS)} -> $action"
}

/**
 * The first main or included file with a `dns { routing { } }` block, with [text] last in the [list]'s block there (the
 * block made when the file has none).
 */
private fun appended(config: HonkConfig, list: RvDnsList, text: String): Pair<HonkSource, String> {
    val name = if (list == RvDnsList.Query) "request" else "response"
    val source = config.sources.firstOrNull { (it.kind == "main" || it.kind == "include") && daeBlock(it.content, "dns", "routing") != null }
        ?: throw EngineException(ErrorCode.InvalidArgument)
    val lines = source.content.lines()
    val block = daeBlock(source.content, "dns", "routing", name)
    val added = if (block != null) {
        val end = block.last + 1
        lines.take(end) + (lines[end].takeWhile(Char::isWhitespace) + INDENT + text) + lines.drop(end)
    } else {
        val end = daeBlock(source.content, "dns", "routing")!!.last + 1
        val indent = lines[end].takeWhile(Char::isWhitespace) + INDENT
        lines.take(end) + listOf("$indent$name {", "$indent$INDENT$text", "$indent}") + lines.drop(end)
    }
    return source to added.joinToString("\n")
}

/** A DNS rule's page in the app; it leaves ([onBack]) once a write landed, and a statement's line opens its file. */
@Composable
internal fun DnsRuleEditRoute(api: NativeApi, query: Boolean, ruleId: String?, onBack: () -> Unit, onOpenSource: (String) -> Unit) {
    val list = if (query) RvDnsList.Query else RvDnsList.Response
    val viewModel: DnsRuleEditViewModel = viewModel(factory = DnsRuleEditViewModel.factory(api, list, ruleId))
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(ui.done) { if (ui.done) onBack() }
    val title = stringResource(if (query) R.string.rv_dns_query_title else R.string.rv_dns_response_title)
    val draft = ui.draft ?: return ui.readFailure?.let { RvRuleFormFailed(title, readBanner(it, viewModel::retry), onBack) } ?: RvSettingsLoading()
    RvDnsRuleEditPage(
        draft,
        viewModel::change,
        ui.upstreams,
        // A new statement has no place yet: the page then shows no 位置.
        ui.at ?: RvNativeRuleAt(0, 0),
        onBack = onBack,
        onApply = viewModel::apply,
        onDelete = viewModel::delete,
        onMove = viewModel::move,
        onOpenSource = { ui.at?.statement?.source?.let(onOpenSource) },
        banner = ui.writeFailure?.let { changeBanner(stringResource(R.string.rv_config_apply_failed), it, viewModel::apply) }
            ?: ui.readFailure?.let { readBanner(it, viewModel::retry) },
    )
}

/** A query matches its name; a response, its answer's addresses. */
private const val DOMAIN = "qname"
private const val ADDRESS = "ip"
private const val INDENT = "    "
