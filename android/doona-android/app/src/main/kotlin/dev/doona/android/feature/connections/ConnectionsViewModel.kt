package dev.doona.android.feature.connections

import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.doona.android.R
import dev.doona.android.engine.ErrorCode
import dev.doona.android.feature.errorCode
import dev.doona.android.feature.messageRes
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
    internal val closeNotices = mutableStateMapOf<String, Int?>()

    internal val ui: StateFlow<RvConnectionsUi> = state.asStateFlow()

    fun load(session: Long?, id: String? = null) = viewModelScope.launch {
        if (state.value.session != session) {
            closeNotices.clear()
            state.value = RvConnectionsUi(session = session)
        }
        if (session == null) return@launch
        val map by lazy(LazyThreadSafetyMode.NONE) { connectionMapper(catalog.groups(), catalog.nodes(), places) }
        while (true) {
            withContext(io) {
                runCatching {
                    val page = connections.list()
                    RvConnectionsUi(page.rows.map(map), clock(), loaded = true, session = session, truncatedTotal = if (page.truncated) page.totalTcp + page.totalUdp else null)
                }
            }.onSuccess { next ->
                if (state.value.session != session) return@launch
                if (next.truncatedTotal == null) closeNotices.keys.removeAll { closed -> next.connections.none { it.id == closed } }
                if (id == null || next.truncatedTotal == null || next.connections.any { it.id == id } || !state.value.loaded) state.value = next
            }.onFailure { if (state.value.session == session) state.value = state.value.copy(error = it.errorCode()) }
            if (id == null) return@launch
            delay(MS_PER_SECOND)
        }
    }

    /**
     * Closes connection [id] of [session]. [closeNotices] keeps the result for the detail page: busy while honk answers,
     * null once the connection is closed or already gone (the page leaves), else why it is still open.
     */
    internal fun close(session: Long, id: String) {
        if (ui.value.session != session) return
        if (id in closeNotices) {
            when (closeNotices[id]) {
                null, R.string.rv_a11y_busy, R.string.rv_conn_not_closable -> return
            }
        }
        closeNotices[id] = R.string.rv_a11y_busy
        viewModelScope.launch {
            val error = withContext(io) { runCatching { connections.close(id) }.exceptionOrNull()?.errorCode() }
            if (ui.value.session == session && id in closeNotices) {
                closeNotices[id] = when (error) {
                    null, ErrorCode.NotFound -> null.also { load(session) }
                    ErrorCode.InvalidArgument -> R.string.rv_conn_not_closable
                    ErrorCode.Busy -> R.string.rv_conn_close_unknown
                    else -> error.messageRes()
                }
            }
        }
    }

    companion object {
        internal fun factory(api: NativeApi, places: NodePlaces = CoreNodePlaces): ViewModelProvider.Factory = viewModelFactory {
            initializer { ConnectionsViewModel(api, places, System::currentTimeMillis) }
        }
    }
}
