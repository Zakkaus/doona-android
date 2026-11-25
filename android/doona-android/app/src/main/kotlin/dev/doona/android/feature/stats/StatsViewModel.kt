package dev.doona.android.feature.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.doona.android.engine.Engine
import dev.doona.android.engine.ScoreState
import dev.doona.android.engine.Usage
import dev.doona.android.feature.errorCode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * The 統計 page: reads `usage()` and `scoreState()` every [pollMs] while the page is shown, with the engine's today
 * totals, subscriptions and catalog, and maps them with [rvStatsState] by the chips' picks (the range, the
 * subscription). A read that fails keeps the last answer, so a passing error does not blank the page. The tools test
 * every node and refresh every subscription; their results come back through the catalog and the subscriptions, and a
 * tool that fails leaves its [failure] until it next succeeds. The reads and the mapping run on [work], off the main
 * thread. A week starts on [firstDay], the locale's.
 */
internal class StatsViewModel(
    private val engine: Engine,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val firstDay: () -> DayOfWeek = { WeekFields.of(Locale.getDefault()).firstDayOfWeek },
    private val pollMs: Long = POLL_MS,
    work: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val reads = flow {
        var usage: Usage? = null
        var score: ScoreState? = null
        while (true) {
            usage = engine.usage().getOrNull() ?: usage
            score = engine.scoreState().getOrNull() ?: score
            emit(usage to score)
            delay(pollMs)
        }
    }

    private val range = MutableStateFlow(RvStatsRange.Today)
    private val subscription = MutableStateFlow<String?>(null)
    private val picks = combine(range, subscription, ::Pair)

    val uiState: StateFlow<RvStatsState> = combine(reads, engine.today, engine.subscriptions, engine.catalog, picks) { (usage, score), today, subscriptions, catalog, (range, subscription) ->
        rvStatsState(StatsInput(usage, today, subscriptions, catalog, score, clock(), zone(), range, subscription, firstDay()))
    }
        .flowOn(work)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), initial())

    private val failed = MutableStateFlow<StatsFailure?>(null)

    /** The last tool that failed, shown as a banner until that tool next succeeds. */
    val failure: StateFlow<StatsFailure?> = failed.asStateFlow()

    /** The range chip's pick: 今天, 本週 or 本月. */
    fun pickRange(picked: RvStatsRange) {
        range.value = picked
    }

    /** The subscription chip's pick, by id; null is 全部訂閱. */
    fun pickSubscription(id: String?) {
        subscription.value = id
    }

    /** 測試所有節點: one latency test of every group. */
    fun testAll() = attempt(StatsTool.TestAll) {
        engine.testLatency(null).exceptionOrNull()?.let { StatsFailure(StatsTool.TestAll, it.errorCode()) }
    }

    /**
     * 更新所有訂閱: each subscription refreshed on its own, so one that fails does not hold the others back; the first that
     * failed names the banner.
     */
    fun updateAll() = attempt(StatsTool.UpdateAll) {
        val results = engine.subscriptions.value.map { sub -> async { sub to engine.updateSubscription(sub.id).exceptionOrNull() } }.awaitAll()
        results.firstNotNullOfOrNull { (sub, error) -> error?.let { StatsFailure(StatsTool.UpdateAll, it.errorCode(), sub.name) } }
    }

    /** The banner's 重試: the failed tool again. */
    fun retry() {
        when (failed.value?.tool) {
            StatsTool.TestAll -> testAll()
            StatsTool.UpdateAll -> updateAll()
            null -> Unit
        }
    }

    /** Runs [tool]: a failure replaces the banner, a success clears that tool's own. */
    private fun attempt(tool: StatsTool, call: suspend CoroutineScope.() -> StatsFailure?) {
        viewModelScope.launch {
            val failure = coroutineScope { call() }
            failed.update { shown -> failure ?: shown?.takeUnless { it.tool == tool } }
        }
    }

    /** The page before the first read answers: the day so far with no bytes, and what the engine already holds. */
    private fun initial() = rvStatsState(
        StatsInput(null, engine.today.value, engine.subscriptions.value, engine.catalog.value, null, clock(), zone(), firstDay = firstDay()),
    )

    companion object {
        /** `usage()` every 5 s while shown, as 活動 reads it. */
        const val POLL_MS = 5_000L
        private const val STOP_TIMEOUT_MS = 5_000L

        fun factory(engine: Engine): ViewModelProvider.Factory = viewModelFactory {
            initializer { StatsViewModel(engine) }
        }
    }
}
