package dev.doona.android.feature.connections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.doona.android.feature.errorCode
import dev.doona.android.feature.model.CoreNodePlaces
import dev.doona.android.feature.model.NodePlaces
import dev.doona.android.honk.NativeApi
import dev.doona.android.honk.NativeCatalog
import dev.doona.android.honk.NativeConnections
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

    internal val ui: StateFlow<RvConnectionsUi> = state.asStateFlow()

    fun load(session: Long?, id: String? = null) = viewModelScope.launch {
        if (state.value.session != session) state.value = RvConnectionsUi(session = session)
        if (session == null) return@launch
        val map by lazy(LazyThreadSafetyMode.NONE) { connectionMapper(catalog.groups(), catalog.nodes(), places) }
        while (true) {
            withContext(io) { runCatching { connections.list().map(map) } }
                .onSuccess { rows ->
                    if (id == null || rows.size < NativeConnections.LIMIT || rows.any { it.id == id } || !state.value.loaded) {
                        state.value = RvConnectionsUi(rows, clock(), loaded = true, session = session)
                    }
                }
                .onFailure { state.value = state.value.copy(error = it.errorCode()) }
            if (id == null) return@launch
            delay(MS_PER_SECOND)
        }
    }

    /** Leave the detail only after honk confirms the close. */
    fun close(id: String, onClosed: () -> Unit) {
        viewModelScope.launch {
            withContext(io) { runCatching { connections.close(id) } }.onSuccess { onClosed() }
        }
    }

    companion object {
        internal fun factory(api: NativeApi, places: NodePlaces = CoreNodePlaces): ViewModelProvider.Factory = viewModelFactory {
            initializer { ConnectionsViewModel(api, places, System::currentTimeMillis) }
        }
    }
}
