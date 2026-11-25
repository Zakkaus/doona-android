package dev.doona.android.feature.policies

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.doona.android.engine.Catalog
import dev.doona.android.engine.Engine
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.RulePage
import dev.doona.android.feature.errorCode
import dev.doona.android.feature.model.RvPolicyState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 策略: the engine's catalog as the page's profile, and the page's changes as engine calls. Each new catalog is read into
 * a profile on [reads], off the main thread, with the nodes' details, the card's rules and today's bytes per rule; a read
 * that fails keeps the profile shown, since no tap caused it. [places] places the nodes by name until 節點's mapper is
 * wired; [clock] is now, for the subscriptions' end dates.
 */
internal class PoliciesViewModel(
    private val engine: Engine,
    private val clock: () -> Long = System::currentTimeMillis,
    private val places: RvNodePlaces = RV_UNKNOWN_PLACES,
    private val reads: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    /** The ad switch and the node order: the engine has neither, so they live here; nothing blocks ads yet, so it starts off. */
    private val local = MutableStateFlow(RvPolicyState(blockAds = false))
    private val failure = MutableStateFlow<PolicyFailure?>(null)

    /** The call that failed last, for the banner's 重試; null once an attempt succeeds. */
    private var retry: (() -> Unit)? = null

    /** The last profile read, kept while the screen is away, so coming back shows it rather than the skeleton. */
    private val profile = MutableStateFlow<RvPolicyProfile?>(null)

    /** The card's rules at their revision; only the reader touches it, one catalog at a time. */
    private var rulePage: RulePage? = null

    val uiState: StateFlow<PoliciesUiState> = channelFlow {
        launch(reads) {
            engine.catalog.filterNotNull().distinctUntilChanged().collectLatest { catalog -> read(catalog)?.let { profile.value = it } }
        }
        combine(engine.ready, engine.catalog, profile, local, failure, ::policiesUiState).collect { send(it) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), current())

    /**
     * The page changed its state to [next]. What differs from the state shown goes to the engine: the mode, 阻擋 QUIC and
     * the main group's node. A tap on the mode already shown changes nothing, so it calls nothing (16c22b3). The order and
     * the ad switch stay here.
     */
    fun onChange(next: RvPolicyState) {
        val shown = uiState.value
        local.update { it.copy(blockAds = next.blockAds, nodeSort = next.nodeSort) }
        val catalog = engine.catalog.value ?: return
        if (next.mode != shown.state.mode) perform(PolicyAction.Mode) { engine.setMode(engineMode(next.mode)) }
        if (next.blockQuic != shown.state.blockQuic) setBlockQuic(catalog, next.blockQuic)
        val main = shown.profile?.main ?: return
        val picked = next.choices[main.id]
        if (picked != null && picked != main.selectedId) {
            val groups = catalog.groups.associateBy { it.id }
            val member = groups[catalog.mainGroupId]?.let { memberFor(it, picked, groups) } ?: picked
            perform(PolicyAction.MainNode) { engine.select(catalog.mainGroupId, member, null) }
        }
    }

    /** Probes the members of every group. */
    fun testAll() = perform(PolicyAction.TestAll) { engine.testLatency(null) }

    /** Refreshes every subscription, one after another in the catalog's order; the first failure is the one shown. */
    fun updateSubscriptions() {
        val ids = engine.catalog.value?.subscriptions?.map { it.id } ?: return
        perform(PolicyAction.Update) {
            ids.map { engine.updateSubscription(it) }.firstOrNull { it.isFailure } ?: Result.success(Unit)
        }
    }

    /**
     * Keeps the template and changes its option. A source that is no template (a subscription's rules) has no option to
     * change, and the engine offers no call for it, so the switch fails as a missing template would.
     */
    private fun setBlockQuic(catalog: Catalog, on: Boolean) {
        val template = catalog.ruleSource.template
        if (template == null) {
            retry = { engine.catalog.value?.let { setBlockQuic(it, on) } }
            failure.value = PolicyFailure(PolicyAction.Quic, ErrorCode.NotFound)
            return
        }
        perform(PolicyAction.Quic) { engine.setRuleSource(template, catalog.ruleSource.options.copy(blockQuic = on)) }
    }

    /**
     * Hands an engine call over at once, in tap order, and shows its failure when it ends, or clears the banner when it
     * succeeds. The engine keeps the order of what it is handed and does not drop it when this screen is left.
     */
    private fun perform(action: PolicyAction, call: suspend () -> Result<*>) {
        viewModelScope.launch {
            val error = call().exceptionOrNull()
            retry = error?.let { { perform(action, call) } }
            failure.value = error?.let { PolicyFailure(action, it.errorCode()) }
        }
    }

    /** The banner's 重試: hands the failed call to the engine again, as it was first made. */
    fun retry() {
        retry?.invoke()
    }

    /** The profile of [catalog], or null when a read failed or the main group cannot be drawn. */
    private suspend fun read(catalog: Catalog): RvPolicyProfile? {
        val ids = catalog.groups.flatMap { it.members }.filterNot { it.isGroup }.map { it.id }.distinct()
        val infos = engine.nodes(ids).getOrElse { return null }.associateBy { it.id }
        val page = rules(catalog.ruleSource.rulesRevision) ?: return null
        val byRule = engine.usage().getOrElse { return null }.today.byRule.associate { it.id to it.up + it.down }
        return rvPolicyProfile(catalog, infos, page.rows, page.total, byRule, clock(), places)
    }

    /**
     * The card's rows at [revision]: the last page while the revision holds, else a new read. A page read at another
     * revision is stale (the source changed between the catalog and the read), so it is read again, a few times at most.
     */
    private suspend fun rules(revision: Long): RulePage? {
        rulePage?.takeIf { it.rulesRevision == revision }?.let { return it }
        repeat(RULE_READS) {
            val page = engine.rules(0, RV_POLICY_CARD_RULES).getOrElse { return null }
            if (page.rulesRevision == revision) return page.also { rulePage = it }
        }
        return null
    }

    /** The engine's flows are StateFlows, so the first frame already shows real values. */
    private fun current() = policiesUiState(engine.ready.value, engine.catalog.value, profile.value, local.value, failure.value)

    companion object {
        fun factory(engine: Engine): ViewModelProvider.Factory = viewModelFactory {
            initializer { PoliciesViewModel(engine) }
        }
    }
}

/**
 * The page's state from the engine's: the mode and 阻擋 QUIC from [catalog], the order and the ad switch from [local]. The
 * choices stay empty: the profile carries each group's real selection.
 */
private fun policiesUiState(
    ready: Boolean,
    catalog: Catalog?,
    profile: RvPolicyProfile?,
    local: RvPolicyState,
    failure: PolicyFailure?,
): PoliciesUiState {
    val state = RvPolicyState(
        mode = catalog?.mode?.let(::rvRouteMode) ?: local.mode,
        blockAds = local.blockAds,
        blockQuic = catalog?.ruleSource?.options?.blockQuic ?: false,
        nodeSort = local.nodeSort,
    )
    return PoliciesUiState(
        loading = !ready || catalog == null || profile == null,
        noSubscription = ready && catalog?.subscriptions?.isEmpty() == true,
        state = state,
        profile = profile,
        failure = failure,
    )
}

/** How often a stale page of rules is read again before the read counts as failed; a newer catalog starts over anyway. */
private const val RULE_READS = 3
