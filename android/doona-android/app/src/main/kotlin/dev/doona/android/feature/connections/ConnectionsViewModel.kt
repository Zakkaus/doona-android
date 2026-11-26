package dev.doona.android.feature.connections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.doona.android.feature.model.CoreNodePlaces
import dev.doona.android.feature.model.NodePlaces
import dev.doona.android.vpn.NativeApi
import dev.doona.android.vpn.honk.NativeCatalog
import dev.doona.android.vpn.honk.NativeConnections
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 連線's state holder, shared by the list and a connection's page: [load] reads the connections honk has open with the
 * groups and nodes they went through; [close] closes one and reads again. A failed read keeps what was shown.
 */
class ConnectionsViewModel internal constructor(
    api: NativeApi,
    private val places: NodePlaces,
    private val clock: () -> Long,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val connections = NativeConnections(api)
    private val catalog = NativeCatalog(api)
    private val state = MutableStateFlow(RvConnectionsUi())
    private var reading: Job? = null

    internal val ui: StateFlow<RvConnectionsUi> = state.asStateFlow()

    fun load() {
        reading?.cancel()
        reading = viewModelScope.launch {
            withContext(io) { runCatching { read() } }.onSuccess { state.value = it }
        }
    }

    /** Closes [id]; one already gone is gone all the same, so the list is read again either way. */
    fun close(id: String) {
        viewModelScope.launch {
            withContext(io) { runCatching { connections.close(id) } }
            load()
        }
    }

    /** Blocks. */
    private fun read(): RvConnectionsUi {
        val open = connections.list()
        return RvConnectionsUi(rvConnections(open, catalog.groups(), catalog.nodes(), places), clock(), loaded = true)
    }

    companion object {
        internal fun factory(api: NativeApi, places: NodePlaces = CoreNodePlaces): ViewModelProvider.Factory = viewModelFactory {
            initializer { ConnectionsViewModel(api, places, System::currentTimeMillis) }
        }
    }
}
