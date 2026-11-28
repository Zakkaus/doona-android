package dev.doona.android.feature.activity

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
import dev.doona.android.honk.NativeApi
import dev.doona.android.honk.NativeCatalog
import dev.doona.android.honk.NativeProbes
import dev.doona.android.honk.NativeRouting
import dev.doona.android.honk.NativeRuntime
import dev.doona.android.honk.OutboundTraffic
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

/**
 * 活動's state holder. [ui] maps honk to the RV page: its profile (subscriptions, nodes, groups, rules) read by [load],
 * the bytes per outbound read every [USAGE_MS] and the traffic every [tickMs] while someone collects [ui], and not
 * otherwise; the lists are mapped on [work]. The tunnel's status, the mode and disconnecting stay with [engine]. It never
 * connects: that needs the activity for the VPN consent, so the page's connect goes to the route. A latency test probes
 * every node through honk and reads the profile again. A failed mode switch or test stays as [RvActivityUi.failure]
 * until it is retried or the next attempt succeeds; a failed read keeps what was shown.
 */
class ActivityViewModel internal constructor(
    private val engine: Engine,
    api: NativeApi,
    crashOffered: StateFlow<Boolean>,
    work: CoroutineDispatcher,
    private val clock: () -> Long,
    private val zone: () -> ZoneId,
    online: Flow<Boolean> = flowOf(true),
    refusals: ConnectRefusals = ConnectRefusals(),
    places: NodePlaces = CoreNodePlaces,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    tickMs: Long = TICK_MS,
) : ViewModel() {
    private val catalog = NativeCatalog(api)
    private val routing = NativeRouting(api)
    private val runtime = NativeRuntime(api)
    private val probes = NativeProbes(api)
    private val failure = MutableStateFlow<RvActivityFailure?>(null)
    private val profile = MutableStateFlow<HonkProfile?>(null)
    private var reading: Job? = null

    /** Each profile with its nodes, mapped once per read, not on each outbound read. */
    private val profileNodes = profile.map { it to honkActivityNodes(it, places) }

    /** The bytes per outbound, read now and every [USAGE_MS] while collected; a failed read keeps the last one. */
    private val outbounds: Flow<List<OutboundTraffic>?> = flow {
        var last: List<OutboundTraffic>? = null
        while (true) {
            last = runCatching { runtime.outbounds() }.getOrNull() ?: last
            emit(last)
            delay(USAGE_MS)
        }
    }.flowOn(io).distinctUntilChanged()

    /** The hero's traffic, read now and every [tickMs] while collected. */
    private val traffic: Flow<ActivityTraffic> = flow {
        while (true) {
            emit(activityTraffic(runCatching { runtime.traffic() }.getOrNull(), runCatching { runtime.history(HERO_SECONDS) }.getOrNull()))
            delay(tickMs)
        }
    }.flowOn(io)

    /** The mode stays the engine's: the profile's once read, the settings' until then. */
    private val mode: Flow<Mode> = combine(engine.catalog.map { it?.mode }.distinctUntilChanged(), engine.settings) { catalog, settings -> catalog ?: settings.mode }

    private val lists: Flow<ActivityCatalog> =
        combine(profileNodes, mode, outbounds) { (profile, nodes), mode, outbounds ->
            val now = clock()
            activityCatalog(ActivityData(profile, mode, outbounds, now, hourOf(now, zone())), nodes)
        }.flowOn(work)

    private val signals = combine(online, refusals.vpn, refusals.notifications, ::ActivitySignals)

    private val flags = combine(engine.ready, crashOffered, failure, signals) { ready, crash, failed, signals -> Flags(ready, crash, failed, signals) }

    internal val ui: StateFlow<RvActivityUi> =
        combine(lists, engine.status, traffic, flags) { lists, status, traffic, flags ->
            activityModel(lists, status, traffic, flags.crash, flags.ready, flags.failure, flags.signals)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial(crashOffered.value))

    /** Reads honk's profile; a failed read keeps the last one. */
    fun load() {
        reading?.cancel()
        reading = viewModelScope.launch { withContext(io) { runCatching { readHonkProfile(catalog, routing) } }.onSuccess { profile.value = it } }
    }

    fun onAction(action: ActivityAction) {
        when (action) {
            ActivityAction.Disconnect -> engine.disconnect()
            is ActivityAction.SetMode -> attempt(RvActivityAttempt.SwitchMode, action) { engine.setMode(action.mode) }
            ActivityAction.TestLatency -> attempt(RvActivityAttempt.TestLatency, action) { testAll() }
            ActivityAction.Retry -> failure.value?.let { onAction(it.retry) }
        }
    }

    /** Probes every group's direct members, then reads the profile again for their results. */
    private suspend fun testAll(): Result<Unit> = withContext(io) {
        runCatching { probes.groups((profile.value?.groups ?: catalog.groups()).map { it.id }) }
    }.also { load() }

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
        val data = ActivityData(null, engine.catalog.value?.mode ?: engine.settings.value.mode, null, now, hourOf(now, zone()))
        val empty = activityCatalog(data, ActivityNodes(emptyList(), allFailed = false)).let {
            ActivityCatalog(it.mode, null, emptyList(), emptyList(), emptyList(), it.cards, allFailed = false, hasSubscriptions = true, mapped = false)
        }
        return activityModel(empty, engine.status.value, ActivityTraffic(), crashOffered, engine.ready.value, null)
    }

    private class Flags(val ready: Boolean, val crash: Boolean, val failure: RvActivityFailure?, val signals: ActivitySignals)

    companion object {
        /** How often 活動 reads the bytes per outbound while it is shown. */
        const val USAGE_MS = 5_000L

        /** honk samples its traffic once a second. */
        const val TICK_MS = 1_000L

        /** The hero draws the last minute. */
        const val HERO_SECONDS = 60

        /**
         * 活動 over honk ([api]) with the crash offer it shows ([crashOffered]: `CrashOffer.offered`), whether a network is
         * up ([online], already steadied by [steadyOnline]) and what the user refused on the way to connecting ([refusals]).
         */
        fun factory(engine: Engine, api: NativeApi, crashOffered: StateFlow<Boolean>, online: Flow<Boolean>, refusals: ConnectRefusals): ViewModelProvider.Factory = viewModelFactory {
            initializer { ActivityViewModel(engine, api, crashOffered, Dispatchers.Default, System::currentTimeMillis, ZoneId::systemDefault, online, refusals) }
        }
    }
}

/** honk's profile as 活動 reads it, through [catalog] and [routing]. Blocks. */
internal fun readHonkProfile(catalog: NativeCatalog, routing: NativeRouting): HonkProfile {
    val rules = routing.rules()
    val groups = catalog.groups()
    return HonkProfile(
        providers = catalog.providers(),
        nodes = catalog.nodes(),
        groups = groups,
        mainGroup = rules.fallback.outbound.takeIf { name -> groups.any { it.name == name } },
        ruleCount = rules.rules.size,
    )
}

/** Where [epochMs] falls in its local day in [zone], in hours from midnight. */
internal fun hourOf(epochMs: Long, zone: ZoneId): Float {
    val time = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalTime()
    return time.toSecondOfDay() / SECONDS_PER_HOUR
}

private const val SECONDS_PER_HOUR = 3_600f
