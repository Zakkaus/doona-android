package dev.doona.android.feature.traffic

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.doona.android.honk.NativeApi
import dev.doona.android.honk.NativeRuntime
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

/**
 * 流量詳情's state holder; it only reads. [load] reads honk's traffic and the samples it keeps, every [tickMs] while the
 * page is shown, since the rate and the chart are live; the range the page picks ([onRange]) cuts its chart from the
 * last load. A failed read keeps the last chart. How long the session has run comes from [clock].
 */
class TrafficViewModel internal constructor(
    private val runtime: NativeRuntime,
    private val clock: () -> Long,
    private val tickMs: Long = TICK_MS,
    io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val range = MutableStateFlow(RV_TRAFFIC_LIVE)

    /** The last chart shown, kept when a read fails. */
    private var shown: RvTrafficChart? = null

    private val reads: Flow<TrafficRead> = flow {
        while (true) {
            emit(load())
            delay(tickMs)
        }
    }.flowOn(io)

    internal val ui: StateFlow<RvTrafficState> =
        combine(reads, range) { read, index -> trafficState(index, read, shown, clock()).also { shown = it.chart } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RvTrafficState(0, 0, false, null, null))

    /** The page shows the range at [index] of [RV_TRAFFIC_RANGE_SECONDS]. */
    fun onRange(index: Int) {
        range.value = index
    }

    /** Blocks: honk's traffic now and its samples, each null when its read failed. */
    private fun load() = TrafficRead(runCatching { runtime.traffic() }.getOrNull(), runCatching { runtime.history() }.getOrNull())

    companion object {
        /** honk samples its traffic once a second. */
        const val TICK_MS = 1_000L

        fun factory(api: NativeApi): ViewModelProvider.Factory = viewModelFactory {
            initializer { TrafficViewModel(NativeRuntime(api), System::currentTimeMillis) }
        }
    }
}
