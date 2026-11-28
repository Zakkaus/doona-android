package dev.doona.android.feature.policies

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.feature.errorCode
import dev.doona.android.feature.model.CoreNodePlaces
import dev.doona.android.feature.model.NodePlaces
import dev.doona.android.feature.model.RvPolicyGroup
import dev.doona.android.feature.model.RvPolicyRule
import dev.doona.android.feature.model.RvRuleMatch
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
 * What 新增服務規則 shows: the main group the new rules send to ([main]), the rule each service has already ([rules], by
 * code), the services ticked ([selected]), and why honk could not be read ([failure]).
 */
@Immutable
internal data class ServiceRulesUiState(
    val main: RvPolicyGroup? = null,
    val rules: Map<String, RvPolicyRule> = emptyMap(),
    val selected: Set<String> = emptySet(),
    val failure: ErrorCode? = null,
    val writeFailure: ErrorCode? = null,
    val done: Boolean = false,
)

/**
 * 新增服務規則 on honk's native API: its groups, nodes, providers and rules, read once on [io]; the ticks are the page's.
 * 加入並套用 ([apply]) writes a rule `domain(geosite:<code>) -> <main group>` for each ticked service, in the page's
 * order, on lines before the fallback in the file honk names (owner, q-service-rules 2026-11-26); [done] once honk
 * took it.
 */
internal class ServiceRulesViewModel(
    private val api: NativeApi,
    private val places: NodePlaces = CoreNodePlaces,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val state = MutableStateFlow(ServiceRulesUiState())
    private var read: HonkPolicyRead? = null

    val uiState: StateFlow<ServiceRulesUiState> = state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            try {
                val read = withContext(io) { HonkPolicyRead.read(api) }.also { this@ServiceRulesViewModel.read = it }
                val groups = read.policyGroups(read.nodes(places))
                val services = buildMap<String, RvPolicyRule> {
                    read.policyRules(groups).forEachIndexed { index, row ->
                        when (val match = row.match) {
                            is RvRuleMatch.GeoSite -> putIfAbsent(match.category.trim().lowercase(), row)

                            is RvRuleMatch.Domains -> {
                                val condition = ruleCondition(read.rules.rules[index].expression)
                                for (arg in topLevel(condition.substringAfter('(').dropLast(1), ",")) {
                                    val value = argument(arg)
                                    if (value.key == "geosite") putIfAbsent(value.value.trim().lowercase(), row)
                                }
                            }

                            else -> Unit
                        }
                    }
                }
                state.update { it.copy(main = read.mainGroupId?.let(groups::get), rules = services.filterKeys { code -> RvServiceData.all.any { it.code == code } }, failure = null, writeFailure = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                state.update { it.copy(failure = error.errorCode()) }
            }
        }
    }

    fun toggle(code: String) = state.update { it.copy(selected = if (code in it.selected) it.selected - code else it.selected + code) }

    fun apply() {
        val read = read ?: return
        val main = read.groups.firstOrNull { it.id == read.mainGroupId }?.name ?: return
        val codes = RvServiceData.all.map { it.code }.filter { it in state.value.selected && it !in state.value.rules }
        if (codes.isEmpty()) return
        state.update { it.copy(writeFailure = null) }
        viewModelScope.launch {
            try {
                withContext(io) {
                    val fallback = read.rules.rules.lastOrNull { it.isFallback }?.source ?: read.rules.fallback.source
                    val config = NativeConfig(api).config()
                    if (config.generationId != read.rules.generationId) throw EngineException(ErrorCode.Busy)
                    val (source, text) = statementAt(config, fallback, null).before(codes.map { "domain(geosite:$it) -> $main" })
                    NativeConfig(api).replace(source, text)
                }
                state.update { it.copy(done = true) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                state.update { it.copy(writeFailure = error.errorCode()) }
            }
        }
    }

    companion object {
        fun factory(api: NativeApi): ViewModelProvider.Factory = viewModelFactory {
            initializer { ServiceRulesViewModel(api) }
        }
    }
}
