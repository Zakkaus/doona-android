package dev.doona.android.feature.activity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.doona.android.designsystem.rv.RvPlace
import dev.doona.android.engine.Catalog
import dev.doona.android.engine.Engine
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
import kotlinx.coroutines.flow.emitAll
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
 * it is retried or the next attempt succeeds.
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
    private val failure = MutableStateFlow<RvActivityFailure?>(null)

    /**
     * Today's usage, read now and every [USAGE_MS] for as long as it is collected; null until a read succeeds. A read
     * equal to the last one is not passed on, so an unchanged day neither maps the lists again nor redraws the page.
     */
    private val usage: Flow<Usage?> = flow {
        var last: Usage? = null
        while (true) {
            last = engine.usage().getOrNull() ?: last
            emit(last)
            delay(USAGE_MS)
        }
    }.distinctUntilChanged()

    /**
     * Each catalog with its nodes, mapped once per catalog revision (plan 2.2), not on each usage read: every leaf node's
     * endpoint and tests are read first; when that read fails the nodes keep the last ones read, by id (orchestrator
     * ruling 2026-11-26, on 06503034).
     */
    private val catalogNodes: Flow<CatalogNodes> = flow {
        var info = emptyMap<String, NodeInfo>()
        emitAll(
            engine.catalog.mapLatest { catalog ->
                val ids = catalog?.let(::leafIds).orEmpty()
                if (ids.isNotEmpty()) engine.nodes(ids).getOrNull()?.let { read -> info = read.associateBy { it.id } }
                CatalogNodes(catalog, activityNodes(catalog, info, placeOf))
            },
        )
    }

    private val rules = ActivityRuleReader(engine)

    private val lists: Flow<ActivityCatalog> =
        // Only yesterday's bytes by now: today's totals move with every second's traffic, and would map 活動 again each time.
        combine(catalogNodes, engine.settings, engine.today.map { it.yesterdayByNow }.distinctUntilChanged(), usage) { mapped, settings, yesterday, usage ->
            ActivityInputs(mapped, settings, yesterday, usage)
        }.mapLatest { inputs ->
            val catalog = inputs.mapped.catalog
            // Without a catalog no rule row is shown, so none is read, and the rows already read stay for the next one.
            val resolved = catalog?.let { rules.resolve(it.ruleSource?.rulesRevision, ruleIds(inputs.usage)) }.orEmpty()
            val now = clock()
            activityCatalog(ActivityData(catalog, inputs.settings, inputs.usage, resolved, inputs.yesterdayByNow ?: 0L, now, hourOf(now, zone())), inputs.mapped.nodes)
        }.flowOn(work)

    private val flags = combine(engine.ready, crashOffered, failure) { ready, crash, failed -> Flags(ready, crash, failed) }

    internal val ui: StateFlow<RvActivityUi> =
        combine(lists, engine.status, engine.traffic, flags) { lists, status, traffic, flags ->
            activityModel(lists, status, traffic, flags.crash, flags.ready, flags.failure)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial(crashOffered.value))

    fun onAction(action: ActivityAction) {
        when (action) {
            ActivityAction.Disconnect -> engine.disconnect()
            is ActivityAction.SetMode -> attempt(RvActivityAttempt.SwitchMode, action) { engine.setMode(action.mode) }
            ActivityAction.TestLatency -> attempt(RvActivityAttempt.TestLatency, action) { engine.testLatency(null) }
            ActivityAction.Retry -> failure.value?.let { onAction(it.retry) }
        }
    }

    /** Runs [call]; its failure is shown until the next attempt, its success clears what was shown. */
    private fun attempt(kind: RvActivityAttempt, action: ActivityAction, call: suspend () -> Result<Unit>) {
        viewModelScope.launch {
            val code = call().exceptionOrNull()?.errorCode()
            failure.value = code?.let { RvActivityFailure(kind, it, action) }
        }
    }

    /** Before the first mapping: the live status and mode over empty lists, shown as loading. */
    private fun initial(crashOffered: Boolean): RvActivityUi {
        val now = clock()
        val data = ActivityData(null, engine.settings.value, null, emptyMap(), engine.today.value.yesterdayByNow ?: 0L, now, hourOf(now, zone()))
        val empty = activityCatalog(data).let {
            ActivityCatalog(it.mode, null, emptyList(), emptyList(), emptyList(), it.cards, allFailed = false, hasSubscriptions = true, mapped = false)
        }
        return activityModel(empty, engine.status.value, engine.traffic.value, crashOffered, engine.ready.value, null)
    }

    private class CatalogNodes(val catalog: Catalog?, val nodes: ActivityNodes)

    private class ActivityInputs(val mapped: CatalogNodes, val settings: Settings, val yesterdayByNow: Long?, val usage: Usage?)

    private class Flags(val ready: Boolean, val crash: Boolean, val failure: RvActivityFailure?)

    companion object {
        /** How often 活動 reads today's usage while it is shown. */
        const val USAGE_MS = 5_000L

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
