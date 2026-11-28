package dev.doona.android.feature.policies

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
import dev.doona.android.app.nativeApi
import dev.doona.android.designsystem.rv.RvDiffAt
import dev.doona.android.designsystem.rv.RvDiffFile
import dev.doona.android.designsystem.rv.RvDiffKind
import dev.doona.android.designsystem.rv.RvDiffLine
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.feature.errorCode
import dev.doona.android.feature.model.CoreNodePlaces
import dev.doona.android.feature.model.NodePlaces
import dev.doona.android.feature.model.RV_BLOCK
import dev.doona.android.feature.model.RV_DIRECT
import dev.doona.android.feature.model.RvMatchKind
import dev.doona.android.feature.model.RvPolicyGroup
import dev.doona.android.feature.settings.RvSettingsLoading
import dev.doona.android.feature.settings.changeBanner
import dev.doona.android.feature.settings.readBanner
import dev.doona.android.honk.HonkConfig
import dev.doona.android.honk.HonkRule
import dev.doona.android.honk.HonkSource
import dev.doona.android.honk.NativeApi
import dev.doona.android.honk.NativeConfig
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
 * What a rule's page shows: the [draft] (null until read), the rule's place and, for a match the form cannot hold, its
 * line ([at]; none for a new rule), the groups it can send to; why a write or the read failed; [done] once a write landed.
 */
@Immutable
internal data class RuleEditUiState(
    val draft: RvRuleDraft? = null,
    val at: RvNativeRuleAt? = null,
    val targets: List<RvPolicyGroup> = emptyList(),
    val writeFailure: ErrorCode? = null,
    val readFailure: ErrorCode? = null,
    val done: Boolean = false,
)

/**
 * A rule's page (全部規則 → a rule, or 新增規則 when [ruleId] is null) on honk's native API. It reads honk's groups, rules
 * and config on [io] at start and on 重試. 套用 writes the rule's line in the file honk names (a new rule goes on a line
 * before the fallback; a paused one stays as a comment), 刪除規則 deletes it, 上移 and 下移 swap it with its neighbour.
 * honk checks each file before it takes it; a line that no longer holds the rule is [ErrorCode.Busy].
 */
internal class RuleEditViewModel(
    private val api: NativeApi,
    private var ruleId: String?,
    private val places: NodePlaces = CoreNodePlaces,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val destination: String? = null,
) : ViewModel() {
    private val state = MutableStateFlow(RuleEditUiState())
    private var read: HonkPolicyRead? = null
    private var failedWrite: (() -> Unit)? = null

    val uiState: StateFlow<RuleEditUiState> = state.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    fun retry() {
        viewModelScope.launch { load() }
    }

    fun retryWrite() {
        failedWrite?.invoke()
    }

    fun change(draft: RvRuleDraft) = state.update { it.copy(draft = draft) }

    fun apply() {
        val draft = state.value.draft?.takeUnless { state.value.at?.sourceOnly == true } ?: return
        write { config, read ->
            val rule = rule(read)
            if (rule == null) {
                val fallback = read.rules.rules.lastOrNull { it.isFallback }
                statementAt(config, fallback?.source ?: read.rules.fallback.source, fallback?.expression ?: "fallback: ${read.rules.fallback.outbound}").before(paused(draft, ruleText(draft, null, read)))
            } else {
                val line = statementAt(config, rule.source, rule.expression)
                line.replaced(paused(draft, ruleText(draft, state.value.at?.statement?.let { ruleCondition(line.code) }, read)))
            }
        }
    }

    fun delete() {
        if (state.value.at?.sourceOnly == true) return
        if (ruleId == null) return state.update { it.copy(done = true) }
        write { config, read ->
            val rule = rule(read) ?: throw EngineException(ErrorCode.NotFound)
            statementAt(config, rule.source, rule.expression).removed()
        }
    }

    /** 上移 or 下移: the rule to 1-based place [index] among the rules, the page then on it there. */
    fun move(index: Int) {
        val read = read?.takeUnless { state.value.at?.sourceOnly == true } ?: return
        val other = read.rules.rules.filterNot { it.isFallback }.getOrNull(index - 1) ?: return
        // honk numbers rules by place, so the moved rule takes the id of the one it changed places with.
        write(landed = { ruleId = other.id }) { config, current ->
            val rule = rule(current) ?: throw EngineException(ErrorCode.NotFound)
            statementAt(config, rule.source, rule.expression).swapped(statementAt(config, other.source, other.expression))
        }
    }

    /** Writes [edit]'s file; once honk took it the page leaves, or with [landed] (a move) it reads the rule again. */
    private fun write(landed: (() -> Unit)? = null, snapshot: HonkPolicyRead? = read, prepared: Pair<HonkSource, String>? = null, edit: (HonkConfig, HonkPolicyRead) -> Pair<HonkSource, String>) {
        snapshot ?: return
        failedWrite = null
        state.update { it.copy(writeFailure = null) }
        viewModelScope.launch {
            var candidate = prepared
            try {
                withContext(io) {
                    val native = NativeConfig(api)
                    val config = native.config()
                    val (source, text) = candidate ?: edit(config, snapshot).also { candidate = it }
                    if (prepared == null || config.sources.firstOrNull { it.id == source.id }?.content != text) native.replace(source, text)
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
                failedWrite = { write(landed, snapshot, candidate, edit) }
                state.update { it.copy(writeFailure = error.errorCode()) }
            }
        }
    }

    private suspend fun load() {
        try {
            val (read, config) = withContext(io) { HonkPolicyRead.read(api) to ruleId?.let { NativeConfig(api).config() } }
            this.read = read
            val groups = read.policyGroups(read.nodes(places))
            val main = read.mainGroupId?.let(groups::get)
            val targets = listOfNotNull(main) + groups.values.filter { it.id != main?.id }
            val rule = ruleId?.let { rule(read) ?: throw EngineException(ErrorCode.NotFound) }
            state.update {
                it.copy(
                    draft = rule?.let { r -> ruleDraft(r, read) } ?: RvRuleDraft(
                        when {
                            destination == null -> RvMatchKind.Suffix
                            '/' in destination -> RvMatchKind.Cidr
                            else -> RvMatchKind.Full
                        },
                        destination.orEmpty(),
                        main?.id ?: RV_DIRECT,
                    ),
                    at = rule?.let { r -> ruleAt(r, read, config) },
                    targets = targets,
                    readFailure = null,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            state.update { it.copy(readFailure = error.errorCode()) }
        }
    }

    private fun rule(read: HonkPolicyRead): HonkRule? = read.rules.rules.firstOrNull { it.id == ruleId && !it.isFallback }

    companion object {
        fun factory(api: NativeApi, ruleId: String?, destination: String?): ViewModelProvider.Factory = viewModelFactory {
            initializer { RuleEditViewModel(api, ruleId, destination = destination) }
        }
    }
}

/** [rule] as the form edits it: its match (a match the form cannot hold keeps its text, for 刪除規則 to name), target, `must`. */
private fun ruleDraft(rule: HonkRule, read: HonkPolicyRead): RvRuleDraft {
    val condition = ruleCondition(rule.expression)
    val (kind, value) = formMatch(condition) ?: (RvMatchKind.Suffix to condition)
    val target = when (rule.outbound) {
        DIRECT -> RV_DIRECT
        BLOCK -> RV_BLOCK
        else -> read.groups.firstOrNull { it.name == rule.outbound }?.id ?: rule.outbound
    }
    return RvRuleDraft(kind, value, target, enabled = true, must = rule.must)
}

/**
 * [rule]'s place among the rules, and its line when the form cannot hold its match; a rule written over several lines
 * of [config] is all its lines, and the page changes nothing.
 */
private fun ruleAt(rule: HonkRule, read: HonkPolicyRead, config: HonkConfig?): RvNativeRuleAt {
    val rules = read.rules.rules.filterNot { it.isFallback }
    val lines = config?.let { statementLines(it, rule.source, rule.expression) }
    if (lines != null) {
        val indent = lines.minOf { (_, text) -> text.takeWhile(Char::isWhitespace).length }
        val hunk = lines.map { (line, text) -> RvDiffLine(RvDiffKind.Same, line, text.drop(indent)) }
        return RvNativeRuleAt(rules.indexOf(rule) + 1, rules.size, RvDiffFile(rule.source!!.file, listOf(hunk)), sourceOnly = true)
    }
    val statement = rule.source?.takeIf { formMatch(ruleCondition(rule.expression)) == null }?.let {
        RvDiffFile(it.file, listOf(listOf(RvDiffLine(RvDiffKind.Same, it.line, rule.expression))))
    }
    return RvNativeRuleAt(rules.indexOf(rule) + 1, rules.size, statement)
}

/** The rule's text: [condition] (a statement's own, or the form's match), the arrow, its target with `(must)` when set. */
private fun ruleText(draft: RvRuleDraft, condition: String?, read: HonkPolicyRead): String {
    val outbound = when (draft.targetId) {
        RV_DIRECT -> DIRECT
        RV_BLOCK -> BLOCK
        else -> read.groups.firstOrNull { it.id == draft.targetId }?.name ?: draft.targetId
    }
    return "${condition ?: matchText(draft.kind, draft.value)} -> $outbound${if (draft.must) "(must)" else ""}"
}

/** A new rule turned off is written as a comment, as a paused one stays. */
private fun paused(draft: RvRuleDraft, text: String) = if (draft.enabled) text else "# $text"

/**
 * A rule's page in the app: on [ruleId]'s rule, or a new one when it is null. It leaves ([onBack]) once a write landed;
 * a line of a match the form cannot hold opens its file ([onOpenSource] with the file's path).
 */
@Composable
internal fun RuleEditRoute(ruleId: String?, onBack: () -> Unit, onOpenSource: (String, Int) -> Unit, destination: String? = null) {
    val viewModel: RuleEditViewModel = viewModel(factory = RuleEditViewModel.factory(nativeApi(), ruleId, destination))
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(ui.done) { if (ui.done) onBack() }
    val title = stringResource(R.string.rv_rule_edit_title)
    val draft = ui.draft ?: return ui.readFailure?.let { RvRuleFormFailed(title, readBanner(it, viewModel::retry), onBack) } ?: RvSettingsLoading()
    val failed = ui.writeFailure?.let { changeBanner(stringResource(R.string.rv_config_apply_failed), it, viewModel::retryWrite) }
    RvRuleEditPage(
        draft,
        viewModel::change,
        onBack = onBack,
        onApply = { viewModel.apply() },
        onDelete = viewModel::delete,
        targets = ui.targets,
        native = ui.at,
        onMove = viewModel::move,
        onOpenSource = { at: RvDiffAt -> ui.at?.statement?.source?.let { onOpenSource(it, at.line) } },
        banner = failed ?: ui.readFailure?.let { readBanner(it, viewModel::retry) },
    )
}

private const val DIRECT = "direct"
private const val BLOCK = "block"
