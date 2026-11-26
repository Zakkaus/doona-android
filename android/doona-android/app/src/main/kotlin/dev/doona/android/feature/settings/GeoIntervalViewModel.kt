package dev.doona.android.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.doona.android.feature.model.RvGeoInterval
import dev.doona.android.vpn.NativeSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * GeoIP/GeoSite's 自動更新 over honk's schedule ([NativeSettings.geoInterval]): read when the page opens and after each
 * choice, which goes to honk. [state] is null until the first read ends; then honk's interval (每天, honk's own, when
 * it names none), or a null [GeoIntervalState.selected] when honk did not answer (VPN off, q-k4-geooffline).
 */
internal class GeoIntervalViewModel(private val honk: NativeSettings, private val io: CoroutineDispatcher = Dispatchers.IO) : ViewModel() {
    private val read = MutableStateFlow<GeoIntervalState?>(null)
    val state: StateFlow<GeoIntervalState?> = read.asStateFlow()

    init {
        load()
    }

    fun select(choice: RvGeoInterval) {
        viewModelScope.launch {
            runCatching { withContext(io) { honk.setGeoInterval(choice) } }
            read()
        }
    }

    private fun load() {
        viewModelScope.launch { read() }
    }

    private suspend fun read() {
        read.value = runCatching { withContext(io) { honk.geoInterval() } }.fold(
            { GeoIntervalState(it ?: RvGeoInterval.Daily) },
            { GeoIntervalState(null) },
        )
    }

    companion object {
        fun factory(honk: NativeSettings): ViewModelProvider.Factory = viewModelFactory {
            initializer { GeoIntervalViewModel(honk) }
        }
    }
}

/** 自動更新's page: honk's [selected] interval, null while honk does not answer. */
internal data class GeoIntervalState(val selected: RvGeoInterval?)
