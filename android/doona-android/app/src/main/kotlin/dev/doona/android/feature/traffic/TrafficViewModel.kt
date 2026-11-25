package dev.doona.android.feature.traffic

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.doona.android.engine.Engine
import dev.doona.android.engine.Status
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

/**
 * 流量詳情's state holder; it only reads. The page tells it the range ([onRange]); it reads `trafficSeries` for that
 * range when the range changes and when the tunnel starts or stops. 1 分 is the live minute: the engine's history of
 * each second, led by that read's older seconds; 5 分, 15 分 and 1 小時 are the windows read. A failed read keeps the
 * last chart. The rates, the session's peaks and totals come from the engine's traffic; how long it has run from [clock].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrafficViewModel internal constructor(private val engine: Engine, private val clock: () -> Long) : ViewModel() {
    private val range = MutableStateFlow(RV_TRAFFIC_LIVE)

    /** The last chart shown, kept when a read fails and while the next range is read. */
    private var shown: RvTrafficChart? = null

    private val reads: Flow<SeriesRead> =
        combine(range, engine.status.map { it is Status.Running }.distinctUntilChanged()) { index, _ -> index }
            .mapLatest { index ->
                val series = engine.trafficSeries(RV_TRAFFIC_RANGE_SECONDS[index].toLong()).getOrNull()
                SeriesRead(index, series?.let { trafficChart(index, it) }, engine.traffic.value.downHistory.size)
            }
            // Until the first read answers, the live minute is the history alone and a window keeps the chart shown.
            .onStart { emit(SeriesRead(range.value, null, engine.traffic.value.downHistory.size)) }

    internal val ui: StateFlow<RvTrafficState> =
        combine(reads, engine.status, engine.traffic) { read, status, traffic ->
            trafficState(read, status, traffic, shown, clock()).also { shown = it.chart }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial())

    /** The page shows the range at [index] of [RV_TRAFFIC_RANGE_SECONDS]. */
    fun onRange(index: Int) {
        range.value = index
    }

    /** Before the first read: the engine's history as the live minute, the rates and the session. */
    private fun initial(): RvTrafficState {
        val traffic = engine.traffic.value
        return trafficState(SeriesRead(RV_TRAFFIC_LIVE, null, traffic.downHistory.size), engine.status.value, traffic, null, clock())
    }

    companion object {
        fun factory(engine: Engine): ViewModelProvider.Factory = viewModelFactory {
            initializer { TrafficViewModel(engine, System::currentTimeMillis) }
        }
    }
}
