package dev.doona.android.feature.connections

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.doona.android.R
import dev.doona.android.designsystem.rv.LocalFrameInsets
import dev.doona.android.designsystem.rv.RvLargeTitle
import dev.doona.android.designsystem.rv.RvScrollPage
import dev.doona.android.feature.messageRes
import dev.doona.android.honk.NativeConnections

/**
 * The 連線 tab (`ConnectionsKey`): [model]'s list, read each time the page starts during [session],
 * and the search kept across a rotation. [onOpen] opens a connection's page by its id; [onTab] switches tabs.
 */
@Composable
internal fun ConnectionsRoute(model: ConnectionsViewModel, session: Long?, onOpen: (String) -> Unit, onTab: (Int) -> Unit) {
    LifecycleResumeEffect(model, session) {
        val job = model.load(session)
        onPauseOrDispose { job.cancel() }
    }
    val ui by model.ui.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf<String?>(null) }
    RvConnectionsPage(
        if (ui.session == session) ui.connections else emptyList(),
        RvConnectionsRoutes(open = onOpen, query = { query = it }, tab = onTab),
        query,
        session != null,
        ui.nowEpochMs,
        loaded = ui.session == session && ui.loaded,
    )
}

/**
 * A connection's page (`ConnectionKey`), from the list [model] last read: 關閉連線 closes it and goes back, and a
 * connection no longer in the list (closed, or gone after a read) leaves the page.
 */
@Composable
internal fun ConnectionRoute(model: ConnectionsViewModel, id: String, session: Long?, onBack: () -> Unit, onNewRule: (String) -> Unit) {
    LifecycleResumeEffect(model, session, id) {
        val job = model.load(session, id)
        onPauseOrDispose { job.cancel() }
    }
    val ui by model.ui.collectAsStateWithLifecycle()
    val snapshot = ui.takeIf { it.session == session }
    val connection = snapshot?.connections?.firstOrNull { it.id == id }
    if (session == null || connection == null) {
        if (session == null || (snapshot?.loaded == true && snapshot.connections.size < NativeConnections.LIMIT)) {
            LaunchedEffect(id) { onBack() }
        } else {
            val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
            val title = stringResource(R.string.rv_nav_connections)
            RvScrollPage(title, insets, onBack = onBack) { block ->
                RvLargeTitle(snapshot?.error?.let { stringResource(it.messageRes()) } ?: title, block, insets)
            }
        }
        return
    }
    RvConnectionDetail(
        connection,
        onBack = onBack,
        onClose = { model.close(id, onBack) },
        onNewRule = { onNewRule(if (connection.hasDomain) connection.host else connection.host + if (':' in connection.host) "/128" else "/32") },
        nowEpochMs = ui.nowEpochMs,
    )
}
