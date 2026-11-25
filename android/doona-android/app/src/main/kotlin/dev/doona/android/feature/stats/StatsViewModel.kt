package dev.doona.android.feature.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.doona.android.engine.Engine
import dev.doona.android.engine.ScoreState
import dev.doona.android.engine.Usage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZoneId

/**
 * The 統計 page: reads `usage()` and `scoreState()` every [pollMs] while the page is shown, with the engine's today
 * totals, subscriptions and catalog, and maps them with [rvStatsState]. A read that fails keeps the last answer, so a
 * passing error does not blank the page. The tools test every node and refresh every subscription; their results come
 * back through the catalog and the subscriptions. The reads and the mapping run on [work], off the main thread.
 */
internal class StatsViewModel(
    private val engine: Engine,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
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

    val uiState: StateFlow<RvStatsState> = combine(reads, engine.today, engine.subscriptions, engine.catalog) { (usage, score), today, subscriptions, catalog ->
        rvStatsState(StatsInput(usage, today, subscriptions, catalog, score, clock(), zone()))
    }
        .flowOn(work)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), initial())

    /** 測試所有節點: one latency test of every group. */
    fun testAll() {
        viewModelScope.launch { engine.testLatency(null) }
    }

    /** 更新所有訂閱: each subscription refreshed on its own, so one that fails does not hold the others back. */
    fun updateAll() {
        for (subscription in engine.subscriptions.value) {
            viewModelScope.launch { engine.updateSubscription(subscription.id) }
        }
    }

    /** The page before the first read answers: the day so far with no bytes, and what the engine already holds. */
    private fun initial() = rvStatsState(
        StatsInput(null, engine.today.value, engine.subscriptions.value, engine.catalog.value, null, clock(), zone()),
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
