package dev.doona.android.feature.connections

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The 連線 tab (`ConnectionsKey`): [model]'s list, read each time the page starts while the tunnel is up ([connected]),
 * and the search kept across a rotation. [onOpen] opens a connection's page by its id; [onTab] switches tabs.
 */
@Composable
internal fun ConnectionsRoute(model: ConnectionsViewModel, connected: Boolean, onOpen: (String) -> Unit, onTab: (Int) -> Unit) {
    LifecycleStartEffect(connected) {
        if (connected) model.load()
        onStopOrDispose {}
    }
    val ui by model.ui.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf<String?>(null) }
    RvConnectionsPage(
        ui.connections,
        RvConnectionsRoutes(open = onOpen, query = { query = it }, tab = onTab),
        query,
        connected,
        ui.nowEpochMs,
        loaded = ui.loaded,
    )
}

/**
 * A connection's page (`ConnectionKey`), from the list [model] last read: 關閉連線 closes it and goes back, and a
 * connection no longer in the list (closed, or gone after a read) leaves the page.
 */
@Composable
internal fun ConnectionRoute(model: ConnectionsViewModel, id: String, onBack: () -> Unit, onNewRule: () -> Unit) {
    val ui by model.ui.collectAsStateWithLifecycle()
    val connection = ui.connections.firstOrNull { it.id == id }
    if (connection == null) {
        LaunchedEffect(id) { onBack() }
        return
    }
    RvConnectionDetail(
        connection,
        onBack = onBack,
        onClose = {
            model.close(id)
            onBack()
        },
        onNewRule = onNewRule,
        nowEpochMs = ui.nowEpochMs,
    )
}
