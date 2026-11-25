package dev.doona.android.feature.activity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.doona.android.designsystem.rv.RvPlace
import dev.doona.android.engine.Catalog
import dev.doona.android.engine.Engine
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.Mode
import dev.doona.android.engine.NodeInfo
import dev.doona.android.engine.Settings
import dev.doona.android.engine.Usage
import dev.doona.android.feature.errorCode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/**
 * 活動's state holder. [ui] maps the engine to the RV page: the lists once per catalog, usage read or rule revision, on
 * [work]; the second's traffic over them. `usage()` is read every [USAGE_MS] while someone collects [ui], and not
 * otherwise. It disconnects, switches the mode and tests latency; it never connects: that needs the activity for the VPN
 * consent, so the page's connect goes to the route. A failed mode switch or test stays as [RvActivityUi.failure] until
 * it is retried, dismissed or the next attempt succeeds.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ActivityViewModel internal constructor(
    private val engine: Engine,
    crashOffered: StateFlow<Boolean>,
    work: CoroutineDispatcher,
    private val clock: () -> Long,
    private val zone: () -> ZoneId,
    private val placeOf: (String) -> RvPlace,
) : ViewModel() {
    constructor(engine: Engine) : this(engine, MutableStateFlow(false), Dispatchers.Default, System::currentTimeMillis, ZoneId::systemDefault, { RvPlace.Other })

    private val error = MutableStateFlow<ErrorCode?>(null)
    private val failure = MutableStateFlow<RvActivityFailure?>(null)

    private val catalogOld = combine(engine.groups, engine.subscriptions) { groups, subscriptions -> groups to subscriptions.isNotEmpty() }

    /** The old 活動 screen's state; it goes with that screen when the RV page takes its route. */
    val uiState: StateFlow<ActivityUiState> =
        combine(engine.status, engine.traffic, engine.settings, catalogOld, error) { status, traffic, settings, (groups, subscribed), error ->
            activityUiState(status, traffic, settings, groups, error, subscribed)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), current())

    /** Today's usage, read now and every [USAGE_MS] for as long as it is collected; null until a read succeeds. */
    private val usage: Flow<Usage?> = flow {
        var last: Usage? = null
        while (true) {
            last = engine.usage().getOrNull() ?: last
            emit(last)
            delay(USAGE_MS)
        }
    }

    /** Every leaf node's endpoint and tests, read again on each catalog. */
    private val nodeInfo: Flow<Map<String, NodeInfo>> = engine.catalog.mapLatest { catalog ->
        val ids = catalog?.let(::leafIds).orEmpty()
        if (ids.isEmpty()) emptyMap() else engine.nodes(ids).getOrNull().orEmpty().associateBy { it.id }
    }

    private val rules = ActivityRuleReader(engine)

    private val lists: Flow<ActivityCatalog> =
        // Only yesterday's bytes by now: today's totals move with every second's traffic, and would map 活動 again each time.
        combine(engine.catalog, engine.settings, engine.today.map { it.yesterdayByNow }.distinctUntilChanged(), usage, nodeInfo) { catalog, settings, yesterday, usage, info ->
            ActivityInputs(catalog, settings, yesterday, usage, info)
        }.mapLatest { inputs ->
            // Without a catalog no rule row is shown, so none is read, and the rows already read stay for the next one.
            val resolved = inputs.catalog?.let { rules.resolve(it.ruleSource?.rulesRevision, ruleIds(inputs.usage)) }.orEmpty()
            val now = clock()
            activityCatalog(ActivityData(inputs.catalog, inputs.settings, inputs.usage, resolved, inputs.info, inputs.yesterdayByNow ?: 0L, now, hourOf(now, zone())), placeOf)
        }.flowOn(work)

    private val flags = combine(engine.warning, engine.ready, crashOffered, failure) { warning, ready, crash, failed -> Flags(warning, ready, crash, failed) }

    internal val ui: StateFlow<RvActivityUi> =
        combine(lists, engine.status, engine.traffic, flags) { lists, status, traffic, flags ->
            activityModel(lists, status, traffic, flags.warning, flags.crash, flags.ready, flags.failure)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial(crashOffered.value))

    fun onAction(action: ActivityAction) {
        when (action) {
            ActivityAction.Disconnect -> engine.disconnect()

            is ActivityAction.SetMode -> attempt(RvActivityAttempt.SwitchMode, action) { engine.setMode(action.mode) }

            ActivityAction.TestLatency -> attempt(RvActivityAttempt.TestLatency, action) { engine.testLatency(null) }

            ActivityAction.Retry -> failure.value?.let { onAction(it.retry) }

            ActivityAction.DismissError -> {
                error.value = null
                failure.value = null
            }
        }
    }

    /** Runs [call]; its failure is shown until the next attempt or a dismissal, its success clears what was shown. */
    private fun attempt(kind: RvActivityAttempt, action: ActivityAction, call: suspend () -> Result<Unit>) {
        viewModelScope.launch {
            val code = call().exceptionOrNull()?.errorCode()
            if (kind == RvActivityAttempt.SwitchMode) error.value = code
            failure.value = code?.let { RvActivityFailure(kind, it, action) }
        }
    }

    /** The engine's flows are StateFlows, so the first frame already shows real values. */
    private fun current() = activityUiState(
        engine.status.value,
        engine.traffic.value,
        engine.settings.value,
        engine.groups.value,
        error.value,
        engine.subscriptions.value.isNotEmpty(),
    )

    /** Before the first mapping: the live status and mode over empty lists, shown as loading. */
    private fun initial(crashOffered: Boolean): RvActivityUi {
        val now = clock()
        val data = ActivityData(null, engine.settings.value, null, emptyMap(), emptyMap(), engine.today.value.yesterdayByNow ?: 0L, now, hourOf(now, zone()))
        val empty = activityCatalog(data, placeOf).let {
            ActivityCatalog(it.mode, null, emptyList(), emptyList(), emptyList(), it.cards, allFailed = false, hasSubscriptions = true, mapped = false)
        }
        return activityModel(empty, engine.status.value, engine.traffic.value, engine.warning.value, crashOffered, engine.ready.value, null)
    }

    private class ActivityInputs(val catalog: Catalog?, val settings: Settings, val yesterdayByNow: Long?, val usage: Usage?, val info: Map<String, NodeInfo>)

    private class Flags(val warning: ErrorCode?, val ready: Boolean, val crash: Boolean, val failure: RvActivityFailure?)

    companion object {
        /** How often 活動 reads today's usage while it is shown. */
        const val USAGE_MS = 5_000L

        fun factory(engine: Engine): ViewModelProvider.Factory = viewModelFactory {
            initializer { ActivityViewModel(engine) }
        }

        /** 活動 with the crash offer it shows ([crashOffered]: `CrashOffer.offered`). */
        fun factory(engine: Engine, crashOffered: StateFlow<Boolean>): ViewModelProvider.Factory = viewModelFactory {
            initializer { ActivityViewModel(engine, crashOffered, Dispatchers.Default, System::currentTimeMillis, ZoneId::systemDefault, { RvPlace.Other }) }
        }
    }
}

/** Where [epochMs] falls in its local day in [zone], in hours from midnight. */
internal fun hourOf(epochMs: Long, zone: ZoneId): Float {
    val time = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalTime()
    return time.toSecondOfDay() / SECONDS_PER_HOUR
}

private const val SECONDS_PER_HOUR = 3_600f
