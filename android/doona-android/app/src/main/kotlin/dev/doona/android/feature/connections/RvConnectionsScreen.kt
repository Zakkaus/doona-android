package dev.doona.android.feature.connections

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import dev.doona.android.R
import dev.doona.android.designsystem.rv.LocalFrameInsets
import dev.doona.android.designsystem.rv.RV_NAV_CONNECTIONS
import dev.doona.android.designsystem.rv.Rv
import dev.doona.android.designsystem.rv.RvAppDisc
import dev.doona.android.designsystem.rv.RvCaption
import dev.doona.android.designsystem.rv.RvCard
import dev.doona.android.designsystem.rv.RvEmptyCaption
import dev.doona.android.designsystem.rv.RvFilterChip
import dev.doona.android.designsystem.rv.RvFloatingNav
import dev.doona.android.designsystem.rv.RvGlass
import dev.doona.android.designsystem.rv.RvGutter
import dev.doona.android.designsystem.rv.RvLongPressTiming
import dev.doona.android.designsystem.rv.RvPageEnd
import dev.doona.android.designsystem.rv.RvRow
import dev.doona.android.designsystem.rv.RvScrollPage
import dev.doona.android.designsystem.rv.RvSearchField
import dev.doona.android.designsystem.rv.RvSearchPill
import dev.doona.android.designsystem.rv.RvTabHeader
import dev.doona.android.designsystem.rv.RvText
import dev.doona.android.designsystem.rv.SEPARATOR
import dev.doona.android.designsystem.rv.rememberRvFormat
import dev.doona.android.feature.model.RvRuleEnd

/** Where the 連線 tab leads: a connection's page, the two filters, the search ([query] null ends it), and the other tabs. */
internal class RvConnectionsRoutes(
    val open: (String) -> Unit = {},
    val filter: () -> Unit = {},
    val query: (String?) -> Unit = {},
    val tab: (Int) -> Unit = {},
)

/**
 * 連線, the third of the four tabs, in the language of 節點: the tabs' bar, its capsule 搜尋連線; a caption counting the
 * open connections and their bytes, and the app and order chips as its control row; then one card of the connections
 * open now ([connections], newest first), each its app's icon, where it went over the app and the rule's group, and its
 * bytes over how long it has been open. A connection opens its page. The capsule starts a search ([query] set): it
 * becomes the field, the count of matches replaces the caption and the chips, and the card keeps the connections whose
 * host or app holds the query; back ends it. With the tunnel off ([connected] false), or nothing matching, the card says
 * so in the library's empty caption, with no action: 連線 is 活動's. Host names are shown from memory and never logged
 * (D21).
 */
@Composable
internal fun RvConnectionsPage(
    connections: List<RvConnection>,
    routes: RvConnectionsRoutes = RvConnectionsRoutes(),
    query: String? = null,
    connected: Boolean = true,
    nowEpochMs: Long,
    loaded: Boolean = true,
) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val scroll = rememberScrollState()
    val hint = stringResource(R.string.rv_conn_search)
    val shown = if (query.isNullOrBlank()) connections else connections.filter { it.host.contains(query, ignoreCase = true) || it.app.contains(query, ignoreCase = true) }
    // The bar has no close: back ends a search.
    BackHandler(enabled = query != null) { routes.query(null) }
    RvScrollPage(
        stringResource(R.string.rv_nav_connections),
        insets,
        scroll = scroll,
        field = { glass, modifier ->
            if (query == null) RvSearchPill({ routes.query("") }, modifier, glass, hint = hint) else SearchField(glass, query, routes, modifier, hint)
        },
        overlay = { glass ->
            RvFloatingNav(RV_NAV_CONNECTIONS, routes.tab, glass, scroll, insets, Modifier.align(Alignment.BottomCenter))
        },
        end = RvPageEnd.Nav,
    ) {
        when {
            !connected || !loaded -> RvTabHeader(insets)
            query == null -> RvTabHeader(insets, caption = { Summary(connections) }, control = { Filters(routes.filter) })
            else -> RvTabHeader(insets, caption = { RvCaption(pluralStringResource(R.plurals.rv_conn_matches, shown.size, shown.size)) })
        }
        when {
            !connected -> Disconnected()
            loaded && connections.isEmpty() -> RvGutter { RvEmptyCaption(stringResource(R.string.rv_conn_empty_title), stringResource(R.string.rv_conn_empty_line)) }
            loaded -> Connections(shown, routes, nowEpochMs)
        }
    }
}

/** The bar's capsule while searching: the field holding [query], with the cursor (and the keyboard) as it opens empty. */
@Composable
private fun SearchField(glass: RvGlass, query: String, routes: RvConnectionsRoutes, modifier: Modifier, hint: String) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(focus) { if (query.isEmpty()) focus.requestFocus() }
    RvSearchField(glass, query, { routes.query(it) }, modifier, focus, hint, onClose = { routes.query(null) })
}

/** The tunnel is off: nothing to list. 連線 is 活動's; the page does not repeat it. */
@Composable
private fun Disconnected() {
    RvGutter { RvEmptyCaption(stringResource(R.string.rv_conn_off_title), stringResource(R.string.rv_conn_off_line)) }
}

/** The card of the [shown] connections, or the empty state when none match; a row opens its connection by id. */
@Composable
private fun Connections(shown: List<RvConnection>, routes: RvConnectionsRoutes, nowEpochMs: Long) {
    RvGutter {
        if (shown.isEmpty()) {
            RvEmptyCaption(stringResource(R.string.rv_conn_none_title), stringResource(R.string.rv_conn_none_line))
        } else {
            // A long press opens the page as a tap does, at the menus' 500 ms (the owner, N6).
            RvLongPressTiming { RvCard { shown.forEach { c -> key(c.id) { RvConnectionRow(c, nowEpochMs) { routes.open(c.id) } } } } }
        }
    }
}

/** How many connections are open and what they moved each way: the header's caption, as 節點's. */
@Composable
private fun Summary(connections: List<RvConnection>) {
    val format = rememberRvFormat()
    val down = format.bytes(connections.sumOf { it.downBytes }).toString()
    val up = format.bytes(connections.sumOf { it.upBytes }).toString()
    RvCaption(pluralStringResource(R.plurals.rv_conn_summary, connections.size, connections.size, down, up))
}

/** The app and order chips, the header's control row: one at each end of the gutter. */
@Composable
private fun Filters(onFilter: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        RvFilterChip(stringResource(R.string.rv_conn_all_apps), onFilter)
        Spacer(Modifier.weight(1f))
        RvFilterChip(stringResource(R.string.rv_conn_newest), onFilter)
    }
}

/**
 * One connection: the app's own icon; host:port, cut in the middle when it is too long, so the start of the name and
 * the port both stay; the app's name and the rule's group (or 直連) under it; and at the end, as a node row's latency
 * over its change, the bytes it moved both ways over how long it has been open. A tap or a long press opens its page
 * ([onClick]), which splits the bytes and shows the whole host.
 */
@Composable
internal fun RvConnectionRow(connection: RvConnection, nowEpochMs: Long, onClick: () -> Unit) {
    val format = rememberRvFormat()
    val bytes = format.bytes(connection.downBytes + connection.upBytes).toString()
    val age = rvAge((nowEpochMs - connection.startedAtEpochMs) / MS_PER_SECOND)
    RvRow(
        { RvAppDisc(connection.packageName) },
        connection.host + PORT + connection.port,
        listOfNotNull(connection.app.ifEmpty { null }, connection.targetName()).joinToString(SEPARATOR),
        onClick = onClick,
        onLongClick = onClick,
        titleLines = 1,
        titleOverflow = TextOverflow.MiddleEllipsis,
        subtitleKeepWords = false,
    ) {
        Column(horizontalAlignment = Alignment.End) {
            RvText(bytes, Rv.type.rowValue, align = TextAlign.End)
            RvText(age, Rv.type.rowSub, color = Rv.colors.textSecondary, align = TextAlign.End)
        }
    }
}

/** How long a connection has been open: seconds under a minute, minutes under an hour, then hours and minutes. */
@Composable
internal fun rvAge(seconds: Long): String = when {
    seconds < SECONDS_PER_MINUTE -> stringResource(R.string.rv_conn_seconds, seconds.toInt())
    seconds < SECONDS_PER_HOUR -> stringResource(R.string.rv_conn_minutes, (seconds / SECONDS_PER_MINUTE).toInt())
    else -> stringResource(R.string.rv_duration, (seconds / SECONDS_PER_HOUR).toInt(), (seconds % SECONDS_PER_HOUR / SECONDS_PER_MINUTE).toInt())
}

internal const val PORT = ":"
internal const val MS_PER_SECOND = 1_000L
private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3_600L

/** Where a connection went: its group's name, 直連, 拒絕 or a missing group; null when honk recorded no outbound. */
@Composable
internal fun RvConnection.targetName(): String? = group?.name ?: end?.let {
    stringResource(
        when (it) {
            RvRuleEnd.Direct -> R.string.mode_direct
            RvRuleEnd.Reject -> R.string.rv_policy_reject
            RvRuleEnd.Missing -> R.string.rv_policy_target_missing
        },
    )
}
