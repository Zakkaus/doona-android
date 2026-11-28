package dev.doona.android.feature.policies

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.doona.android.R
import dev.doona.android.engine.Engine
import dev.doona.android.engine.ErrorCode
import dev.doona.android.feature.errorCode
import dev.doona.android.feature.model.CoreNodePlaces
import dev.doona.android.feature.model.NodePlaces
import dev.doona.android.feature.model.RvLabel
import dev.doona.android.feature.model.RvRouteMode
import dev.doona.android.feature.model.RvRuleSource
import dev.doona.android.feature.model.rvTemplate
import dev.doona.android.feature.subscriptions.rvSubscription
import dev.doona.android.honk.HonkConfig
import dev.doona.android.honk.HonkDnsRules
import dev.doona.android.honk.NativeApi
import dev.doona.android.honk.NativeConfig
import dev.doona.android.honk.NativeRouting
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What 生效設定 shows: the mode and the configuration as the cards draw it ([config], null until the first read), the
 * text 複製 and 匯出 hand on, whether it was cut ([truncated]; honk's export never is), and why the last read failed ([failure]).
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
 * 生效設定 on honk's native API: its groups, nodes, providers, rules, config files, DNS rules and the files' export,
 * read on [io] at start and on 重試; the mode is the [engine]'s, and how many apps 依 App lists comes from its settings.
 * A read that fails shows its failure with 重試 over the configuration last read, if there is one.
 */
internal class EffectiveConfigViewModel(
    private val engine: Engine,
    private val api: NativeApi,
    private val places: NodePlaces = CoreNodePlaces,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val read = MutableStateFlow<EffectiveRead?>(null)
    private val failure = MutableStateFlow<ErrorCode?>(null)
    private var reading: Job? = null

    val uiState: StateFlow<EffectiveConfigUiState> = combine(read, failure, engine.catalog, engine.settings) { read, failure, catalog, settings ->
        EffectiveConfigUiState(
            mode = catalog?.mode?.let(::rvRouteMode) ?: RvRouteMode.Rule,
            config = read?.let { rvEffectiveConfig(it.policies, it.config, it.dns, settings.perApp.packages.size, places) },
            text = read?.text,
            failure = failure,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EffectiveConfigUiState())

    init {
        load()
    }

    /** The banner's 重試: reads again. */
    fun retry() = load()

    private fun load() {
        reading?.cancel()
        reading = viewModelScope.launch {
            try {
                read.value = withContext(io) {
                    val config = NativeConfig(api)
                    EffectiveRead(HonkPolicyRead.read(api), config.config(), NativeRouting(api).dnsRules(), config.export())
                }
                failure.value = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failure.value = error.errorCode()
            }
        }
    }

    companion object {
        fun factory(engine: Engine, api: NativeApi): ViewModelProvider.Factory = viewModelFactory {
            initializer { EffectiveConfigViewModel(engine, api) }
        }
    }
}

/** One read of honk for 生效設定; [text] is honk's export of its files, which 複製 and 匯出 hand on. */
private class EffectiveRead(val policies: HonkPolicyRead, val config: HonkConfig, val dns: HonkDnsRules, val text: String)

/**
 * 生效設定's cards from honk: the groups (the main one is the group the rules' fallback names), every rule, the template
 * the routing is, the subscriptions, the DNS servers' addresses and what the request fallback's server goes through (the
 * main group is 主節點), and the [apps] 依 App lists.
 */
internal fun rvEffectiveConfig(policies: HonkPolicyRead, config: HonkConfig, dns: HonkDnsRules, apps: Int, places: NodePlaces): RvEffectiveConfig {
    val groups = policies.policyGroups(policies.nodes(places))
    val main = policies.mainGroupId?.let(groups::get)
    val upstreams = honkUpstreams(config)
    val via = fallbackUpstream(upstreams, dns)?.via
    val mainName = policies.groups.firstOrNull { it.id == main?.id }?.name
    return RvEffectiveConfig(
        main = main,
        groups = groups.values.filter { it.id != main?.id },
        rules = policies.policyRules(groups),
        source = honkRuleSource(HonkRouting.of(config)),
        subscriptions = policies.subscriptions.map { rvSubscription(it, null) },
        dns = upstreams.map { dnsShown(it.address) },
        dnsVia = when (via) {
            null, DIRECT -> RvLabel.Res(R.string.mode_direct)
            mainName -> RvLabel.Res(R.string.rv_main_node)
            else -> RvLabel.Text(via)
        },
        proxiedApps = apps,
    )
}

private const val DIRECT = "direct"
