package dev.doona.android.feature.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.doona.android.feature.activity.HonkProfile
import dev.doona.android.feature.activity.readHonkProfile
import dev.doona.android.feature.errorCode
import dev.doona.android.vpn.NativeApi
import dev.doona.android.vpn.honk.NativeCatalog
import dev.doona.android.vpn.honk.NativeProbes
import dev.doona.android.vpn.honk.NativeRouting
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * The 統計 page: reads honk's profile ([api]: providers, nodes, groups, rules) by [load] and maps it with [rvStatsState]
 * by the chips' picks (the range, the subscription) on [work]. honk keeps no usage to poll, so nothing is read while the
 * page stays open. A read that fails keeps the last answer. The tools probe every group's members and refresh every
 * provider, then read the profile again; a tool that fails leaves its [failure] until it next succeeds. A week starts on
 * [firstDay], the locale's.
 */
internal class StatsViewModel(
    api: NativeApi,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val firstDay: () -> DayOfWeek = { WeekFields.of(Locale.getDefault()).firstDayOfWeek },
    work: CoroutineDispatcher = Dispatchers.Default,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val catalog = NativeCatalog(api)
    private val routing = NativeRouting(api)
    private val probes = NativeProbes(api)
    private val profile = MutableStateFlow<HonkProfile?>(null)
    private var reading: Job? = null

    private val range = MutableStateFlow(RvStatsRange.Today)
    private val subscription = MutableStateFlow<String?>(null)

    val uiState: StateFlow<RvStatsState> = combine(profile, range, subscription) { profile, range, subscription ->
        rvStatsState(StatsInput(profile, clock(), zone(), range, subscription, firstDay()))
    }
        .flowOn(work)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), rvStatsState(StatsInput(null, clock(), zone(), firstDay = firstDay())))

    private val failed = MutableStateFlow<StatsFailure?>(null)

    /** The last tool that failed, shown as a banner until that tool next succeeds. */
    val failure: StateFlow<StatsFailure?> = failed.asStateFlow()

    /** Reads honk's profile; a failed read keeps the last one. */
    fun load() {
        reading?.cancel()
        reading = viewModelScope.launch { withContext(io) { runCatching { readHonkProfile(catalog, routing) } }.onSuccess { profile.value = it } }
    }

    /** The range chip's pick: 今天, 本週 or 本月. */
    fun pickRange(picked: RvStatsRange) {
        range.value = picked
    }

    /** The subscription chip's pick, by id; null is 全部訂閱. */
    fun pickSubscription(id: String?) {
        subscription.value = id
    }

    /** 測試所有節點: a probe of every group's members. */
    fun testAll() = attempt(StatsTool.TestAll) {
        val groups = profile.value?.groups ?: catalog.groups()
        runCatching { probes.groups(groups.map { it.id }) }.exceptionOrNull()?.let { StatsFailure(StatsTool.TestAll, it.errorCode()) }
    }

    /**
     * 更新所有訂閱: each provider refreshed on its own, so one that fails does not hold the others back; the first that
     * failed names the banner.
     */
    fun updateAll() = attempt(StatsTool.UpdateAll) {
        val providers = profile.value?.providers ?: catalog.providers()
        val results = providers.map { provider -> async { provider to runCatching { catalog.refreshProvider(provider.id) }.exceptionOrNull() } }.awaitAll()
        results.firstNotNullOfOrNull { (provider, error) -> error?.let { StatsFailure(StatsTool.UpdateAll, it.errorCode(), provider.name) } }
    }

    /** The banner's 重試: the failed tool again. */
    fun retry() {
        when (failed.value?.tool) {
            StatsTool.TestAll -> testAll()
            StatsTool.UpdateAll -> updateAll()
            null -> Unit
        }
    }

    /** Runs [tool] on [io], then reads the profile again: a failure replaces the banner, a success clears that tool's own. */
    private fun attempt(tool: StatsTool, call: suspend CoroutineScope.() -> StatsFailure?) {
        viewModelScope.launch {
            val failure = withContext(io) { runCatching { coroutineScope { call() } }.getOrElse { StatsFailure(tool, it.errorCode()) } }
            failed.update { shown -> failure ?: shown?.takeUnless { it.tool == tool } }
            load()
        }
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        fun factory(api: NativeApi): ViewModelProvider.Factory = viewModelFactory {
            initializer { StatsViewModel(api) }
        }
    }
}
