package dev.doona.android.feature.policies

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.doona.android.R
import dev.doona.android.engine.Catalog
import dev.doona.android.engine.EffectiveConfig
import dev.doona.android.engine.Engine
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.RuleRow
import dev.doona.android.engine.RuleTarget
import dev.doona.android.feature.errorCode
import dev.doona.android.feature.model.RvLabel
import dev.doona.android.feature.model.RvRouteMode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * What 生效設定 shows: the mode and the configuration as the cards draw it ([config], null until the first read), the
 * text 複製 and 匯出 hand on, whether the core cut it ([truncated]), and why the first read failed ([failure]).
 */
@Immutable
internal data class EffectiveConfigUiState(
    val mode: RvRouteMode = RvRouteMode.Rule,
    val config: RvEffectiveConfig? = null,
    val text: String? = null,
    val truncated: Boolean = false,
    val failure: ErrorCode? = null,
)

/**
 * 生效設定: the core's `effectiveConfig` with the catalog's groups, every rule of the source and how many apps 依 App
 * lists, read on [reads] whenever the catalog or the settings change. A read that fails shows its failure with 重試,
 * which reads again, over the configuration last read if there is one, as 策略 and the group page do.
 */
internal class EffectiveConfigViewModel(
    private val engine: Engine,
    private val places: RvNodePlaces = RV_UNKNOWN_PLACES,
    private val reads: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val read = MutableStateFlow(EffectiveConfigUiState())
    private val again = MutableStateFlow(0)

    val uiState: StateFlow<EffectiveConfigUiState> = channelFlow {
        launch(reads) {
            combine(engine.catalog.filterNotNull(), engine.settings, again) { catalog, settings, _ -> catalog to settings.perApp.packages.size }
                .collectLatest { (catalog, apps) -> read.value = read(catalog, apps) }
        }
        read.collect { send(it) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), read.value)

    /** The banner's 重試: reads again. */
    fun retry() {
        again.value++
    }

    private suspend fun read(catalog: Catalog, apps: Int): EffectiveConfigUiState {
        val effective = engine.effectiveConfig().getOrElse { return failed(it) }
        val rows = engine.allRules(effective.ruleSource.rulesRevision).getOrElse { return failed(it) }
        return EffectiveConfigUiState(
            mode = rvRouteMode(effective.mode),
            config = rvEffectiveConfig(effective, catalog, rows, apps, places),
            text = effective.text,
            truncated = effective.truncated,
        )
    }

    private fun failed(error: Throwable): EffectiveConfigUiState =
        read.value.takeIf { it.config != null }?.copy(failure = error.errorCode()) ?: EffectiveConfigUiState(failure = error.errorCode())

    companion object {
        fun factory(engine: Engine): ViewModelProvider.Factory = viewModelFactory {
            initializer { EffectiveConfigViewModel(engine) }
        }
    }
}

/**
 * 生效設定's cards from the core's [effective] configuration: [catalog]'s groups (the main one by the configuration's
 * main id, null when no group has it), every rule [rows] lists, where the rules come from, the subscriptions, the DNS
 * servers and what their queries go through (the main group is 主節點), and the [apps] 依 App lists.
 */
internal fun rvEffectiveConfig(effective: EffectiveConfig, catalog: Catalog, rows: List<RuleRow>, apps: Int, places: RvNodePlaces): RvEffectiveConfig {
    val nodes = rvNodes(catalog, emptyMap(), places)
    val byId = catalog.groups.associateBy { it.id }
    val groups = catalog.groups.map { rvPolicyGroup(it, byId, nodes) }.associateBy { it.id }
    val main = groups[effective.mainGroupId]
    val dnsVia = when (val via = effective.dnsVia) {
        RuleTarget.Direct -> RvLabel.Res(R.string.mode_direct)
        RuleTarget.Reject -> RvLabel.Res(R.string.rv_policy_reject)
        is RuleTarget.Group -> if (via.id == effective.mainGroupId) RvLabel.Res(R.string.rv_main_node) else RvLabel.Text(byId[via.id]?.name ?: via.id)
    }
    return RvEffectiveConfig(
        main = main,
        groups = groups.values.filter { it.id != main?.id },
        rules = rows.map { rvPolicyRule(it, groups, 0L) },
        source = rvRuleSource(effective.ruleSource, catalog.subscriptions),
        subscriptions = catalog.subscriptions.map(::rvSubscription),
        dns = effective.dnsUpstreams,
        dnsVia = dnsVia,
        proxiedApps = apps,
    )
}
