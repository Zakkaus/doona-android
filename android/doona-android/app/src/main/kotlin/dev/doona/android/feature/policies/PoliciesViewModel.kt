package dev.doona.android.feature.policies

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.doona.android.engine.Engine
import dev.doona.android.engine.Mode
import dev.doona.android.feature.errorCode
import dev.doona.android.feature.model.CoreNodePlaces
import dev.doona.android.feature.model.NodePlaces
import dev.doona.android.feature.model.RvPolicyState
import dev.doona.android.vpn.NativeApi
import dev.doona.android.vpn.honk.HonkConfig
import dev.doona.android.vpn.honk.HonkDnsRules
import dev.doona.android.vpn.honk.NativeCatalog
import dev.doona.android.vpn.honk.NativeConfig
import dev.doona.android.vpn.honk.NativeProbes
import dev.doona.android.vpn.honk.NativeRouting
import dev.doona.android.vpn.honk.SelectionNetwork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 策略 on honk's native API: its groups, nodes, subscriptions, rules, the file its routing is in and its DNS rules, read
 * into a profile on [io] at start, after each change made here, and on 重試. The changes go to honk; the mode alone stays
 * the [engine]'s. A read that fails keeps the profile shown and shows its failure with 重試. [places] places the nodes by
 * name; [clock] is now, for the subscriptions' end dates.
 */
internal class PoliciesViewModel(
    private val engine: Engine,
    private val api: NativeApi,
    private val clock: () -> Long = System::currentTimeMillis,
    private val places: NodePlaces = CoreNodePlaces,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    /** The node order, which honk does not have. */
    private val local = MutableStateFlow(RvPolicyState())
    private val read = MutableStateFlow<PolicyRead?>(null)
    private val failure = MutableStateFlow<PolicyFailure?>(null)

    /** The call that failed last, for the banner's 重試; null once an attempt succeeds. */
    private var retry: (() -> Unit)? = null
    private var reading: Job? = null

    val uiState: StateFlow<PoliciesUiState> =
        combine(engine.catalog, read, local, failure) { catalog, read, local, failure -> policiesUiState(catalog?.mode, read, local, failure) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), current())

    init {
        load()
    }

    /** Reads honk again; a read still running gives way to it. */
    fun load() {
        reading?.cancel()
        reading = viewModelScope.launch {
            try {
                read.value = withContext(io) { readPolicies() }
                failure.update { it?.takeIf { failed -> failed.action != PolicyAction.Read } }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                retry = ::load
                failure.value = PolicyFailure(PolicyAction.Read, error.errorCode())
            }
        }
    }

    /**
     * The page changed its state to [next]. What differs from the state shown goes out: the mode to the engine, the two
     * switches and the main group's node to honk. A tap on the mode already shown changes nothing (16c22b3). The order
     * stays here.
     */
    fun onChange(next: RvPolicyState) {
        val shown = uiState.value
        local.update { it.copy(nodeSort = next.nodeSort) }
        if (next.mode != shown.state.mode) perform(PolicyAction.Mode, reread = false) { engine.setMode(engineMode(next.mode)).getOrThrow() }
        val read = read.value ?: return
        if (next.blockAds != shown.state.blockAds) {
            perform(PolicyAction.Ads) { adsEdit(read.config, read.dns, next.blockAds)?.let { (source, text) -> NativeConfig(api).replace(source, text) } }
        }
        val routing = read.routing
        if (next.blockQuic != shown.state.blockQuic && routing?.template != null) {
            perform(PolicyAction.Quic) { NativeConfig(api).replace(routing.source, routing.withBlockQuic(next.blockQuic)) }
        }
        val main = shown.profile?.main ?: return
        val picked = next.choices[main.id]
        if (picked != null && picked != main.selectedId) {
            val groups = read.policies.groups.associateBy { it.id }
            val member = groups[main.id]?.let { memberFor(it, picked, groups) } ?: picked
            perform(PolicyAction.MainNode) { NativeCatalog(api).select(main.id, member, SelectionNetwork.Both) }
        }
    }

    /** Probes the members of every group. */
    fun testAll() {
        val ids = read.value?.policies?.groups?.map { it.id } ?: return
        perform(PolicyAction.TestAll) { NativeProbes(api).groups(ids) }
    }

    /** Refreshes every subscription, one after another in honk's order; the first failure is the one shown. */
    fun updateSubscriptions() {
        val ids = read.value?.policies?.subscriptions?.map { it.id } ?: return
        perform(PolicyAction.Update) {
            val catalog = NativeCatalog(api)
            ids.map { id -> runCatching { catalog.refreshProvider(id) } }.firstOrNull { it.isFailure }?.getOrThrow()
        }
    }

    /**
     * Runs [call] on [io] at once, in tap order, and shows its failure when it ends, or clears the banner when it
     * succeeds; honk's changes are read back after it ([reread]).
     */
    private fun perform(action: PolicyAction, reread: Boolean = true, call: suspend () -> Unit) {
        viewModelScope.launch {
            val error = try {
                withContext(io) { call() }
                null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                error
            }
            retry = error?.let { { perform(action, reread, call) } }
            failure.value = error?.let { PolicyFailure(action, it.errorCode()) }
            if (reread) load()
        }
    }

    /** The banner's 重試: makes the failed call again, as it was first made. */
    fun retry() {
        retry?.invoke()
    }

    private fun readPolicies(): PolicyRead {
        val policies = HonkPolicyRead.read(api)
        val config = NativeConfig(api).config()
        val dns = NativeRouting(api).dnsRules()
        val routing = HonkRouting.of(config)
        return PolicyRead(policies, config, dns, routing, policies.profile(places, clock(), routing))
    }

    private fun current() = policiesUiState(engine.catalog.value?.mode, read.value, local.value, failure.value)

    companion object {
        fun factory(engine: Engine, api: NativeApi): ViewModelProvider.Factory = viewModelFactory {
            initializer { PoliciesViewModel(engine, api) }
        }
    }
}

/** One read of honk for 策略: what the profile was drawn from, kept for the changes made on it. */
private class PolicyRead(
    val policies: HonkPolicyRead,
    val config: HonkConfig,
    val dns: HonkDnsRules,
    val routing: HonkRouting?,
    val profile: RvPolicyProfile,
)

/** The page's state: the mode as the engine has it, the two switches as honk runs them, the order from [local]. */
private fun policiesUiState(mode: Mode?, read: PolicyRead?, local: RvPolicyState, failure: PolicyFailure?) = PoliciesUiState(
    loading = read == null && failure?.action != PolicyAction.Read,
    noSubscription = read?.policies?.subscriptions?.isEmpty() == true,
    state = RvPolicyState(
        mode = mode?.let(::rvRouteMode) ?: local.mode,
        blockAds = read?.dns?.let(::blocksAds) ?: false,
        blockQuic = read?.routing?.blockQuic ?: false,
        nodeSort = local.nodeSort,
    ),
    profile = read?.profile,
    failure = failure,
)
