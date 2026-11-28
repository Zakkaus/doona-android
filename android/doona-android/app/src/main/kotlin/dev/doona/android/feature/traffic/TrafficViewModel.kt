package dev.doona.android.feature.traffic

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.doona.android.engine.Status
import dev.doona.android.honk.NativeApi
import dev.doona.android.honk.NativeRuntime
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest

/**
 * 流量詳情 reads traffic every [tickMs] while shown and connected. A failed read, or a return to the same tunnel,
 * keeps what the current tunnel showed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrafficViewModel internal constructor(
    private val runtime: NativeRuntime,
    private val clock: () -> Long,
    status: Flow<Status>,
    private val tickMs: Long = TICK_MS,
    io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val range = MutableStateFlow(RV_TRAFFIC_LIVE)
    private var sessionStart: Long? = null
    private var shown = EMPTY

    private val reads: Flow<TrafficRead> = flow {
        while (true) {
            emit(load())
            delay(tickMs)
        }
    }.flowOn(io)

    internal val ui: StateFlow<RvTrafficState> =
        status.transformLatest { status ->
            val start = (status as? Status.Running)?.sinceEpochMs
            if (start != sessionStart) {
                shown = EMPTY
                sessionStart = start
            }
            emit(shown)
            if (status is Status.Running) {
                emitAll(combine(reads, range) { read, index -> trafficState(index, read, shown.chart, clock()).also { shown = it } })
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EMPTY)

    /** The page shows the range at [index] of [RV_TRAFFIC_RANGE_SECONDS]. */
    fun onRange(index: Int) {
        range.value = index
    }

    /** Blocks: honk's traffic now and its samples, each null when its read failed. */
    private fun load() = TrafficRead(runCatching { runtime.traffic() }.getOrNull(), runCatching { runtime.history() }.getOrNull())

    companion object {
        private val EMPTY = RvTrafficState(0, 0, false, null, null)

        /** honk samples its traffic once a second. */
        const val TICK_MS = 1_000L

        fun factory(api: NativeApi, status: Flow<Status>): ViewModelProvider.Factory = viewModelFactory {
            initializer { TrafficViewModel(NativeRuntime(api), System::currentTimeMillis, status) }
        }
    }
}
