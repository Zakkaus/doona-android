package dev.doona.android.prototype.rv.connections

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import dev.doona.android.R
import dev.doona.android.designsystem.theme.LocalFrameInsets
import dev.doona.android.prototype.rv.LocalRvNav
import dev.doona.android.prototype.rv.RV_NAV_CONNECTIONS
import dev.doona.android.prototype.rv.RV_NAV_FOUR
import dev.doona.android.prototype.rv.Rv
import dev.doona.android.prototype.rv.RvAppDisc
import dev.doona.android.prototype.rv.RvCard
import dev.doona.android.prototype.rv.RvEmptyCaption
import dev.doona.android.prototype.rv.RvFilterChip
import dev.doona.android.prototype.rv.RvFloatingNav
import dev.doona.android.prototype.rv.RvGlassButton
import dev.doona.android.prototype.rv.RvGutter
import dev.doona.android.prototype.rv.RvLargeTitle
import dev.doona.android.prototype.rv.RvLongPressTiming
import dev.doona.android.prototype.rv.RvRow
import dev.doona.android.prototype.rv.RvScrollPage
import dev.doona.android.prototype.rv.RvSpace
import dev.doona.android.prototype.rv.RvText
import dev.doona.android.prototype.rv.RvTextField
import dev.doona.android.prototype.rv.RvTheme
import dev.doona.android.prototype.rv.data.RV_NOW
import dev.doona.android.prototype.rv.nodes.SEPARATOR
import dev.doona.android.prototype.rv.policy.RvConnection
import dev.doona.android.prototype.rv.policy.RvPolicyData
import dev.doona.android.prototype.rv.policy.targetName
import dev.doona.android.prototype.rv.rememberRvFormat
import dev.doona.android.prototype.rv.rvNavClearance
import dev.doona.android.prototype.rv.rvSystemScheme

/** Where the 連線 tab leads: a connection's page, the two filters, the search ([query] null ends it), and the other tabs. */
internal class RvConnectionsRoutes(
    val open: (Int) -> Unit = {},
    val filter: () -> Unit = {},
    val query: (String?) -> Unit = {},
    val tab: (Int) -> Unit = {},
)

/**
 * 連線, the proposed fourth tab (features-gap Q5, option A), in the language of 節點: the large title with search in the
 * bar; a line counting the open connections and their bytes; the app and order chips; then one card of the connections
 * open now ([connections], newest first), each its app's icon, where it went over the app and the rule's group, and its
 * bytes over how long it has been open. A connection opens its page. While [query] is set, the search field replaces
 * the line and the chips and the card keeps the connections whose host or app holds it. With the tunnel off
 * ([connected] false), or nothing matching, the card says so in the library's empty caption, with no action: 連線 is
 * 活動's, and the bar's close ends a search. The bar always has four tabs here. Host names are shown from memory and
 * never logged (D21).
 */
@Composable
internal fun RvConnectionsPage(
    connections: List<RvConnection>,
    routes: RvConnectionsRoutes = RvConnectionsRoutes(),
    query: String? = null,
    connected: Boolean = true,
    nowEpochMs: Long = RV_NOW,
) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val scroll = rememberScrollState()
    val title = stringResource(R.string.rv_nav_connections)
    val search = stringResource(if (query == null) R.string.rv_conn_search else R.string.rv_conn_search_end)
    CompositionLocalProvider(LocalRvNav provides RV_NAV_FOUR) {
        RvScrollPage(
            title,
            insets,
            scroll = scroll,
            actions = if (connected) {
                { glass -> RvGlassButton(glass, if (query == null) R.drawable.rv_sym_search else R.drawable.rv_sym_close, search) { routes.query(if (query == null) "" else null) } }
            } else {
                null
            },
            overlay = { glass ->
                RvFloatingNav(RV_NAV_CONNECTIONS, routes.tab, glass, scroll, insets, Modifier.align(Alignment.BottomCenter))
            },
        ) { titleBlock ->
            RvLargeTitle(title, titleBlock, insets)
            if (connected) Connections(connections, query, routes, nowEpochMs) else Disconnected()
            Spacer(Modifier.height(rvNavClearance(insets)))
        }
    }
}

/** The tunnel is off: nothing to list. 連線 is 活動's; the page does not repeat it. */
@Composable
private fun Disconnected() {
    RvGutter { RvEmptyCaption(stringResource(R.string.rv_conn_off_title), stringResource(R.string.rv_conn_off_line)) }
}

/** The line or the search field, the chips, and the card of connections, or the empty state when none match. */
@Composable
private fun Connections(connections: List<RvConnection>, query: String?, routes: RvConnectionsRoutes, nowEpochMs: Long) {
    val shown = if (query.isNullOrBlank()) connections else connections.filter { it.host.contains(query, ignoreCase = true) || it.app.contains(query, ignoreCase = true) }
    if (query == null) {
        Summary(connections)
        Row(Modifier.padding(horizontal = RvSpace.gutter), verticalAlignment = Alignment.CenterVertically) {
            RvFilterChip(stringResource(R.string.rv_conn_all_apps), routes.filter)
            Spacer(Modifier.weight(1f))
            RvFilterChip(stringResource(R.string.rv_conn_newest), routes.filter)
        }
    } else {
        RvTextField(
            query,
            { routes.query(it) },
            stringResource(R.string.rv_conn_search),
            Modifier.padding(horizontal = RvSpace.gutter),
            placeholder = stringResource(R.string.rv_conn_search_hint),
            supporting = pluralStringResource(R.plurals.rv_conn_matches, shown.size, shown.size),
        )
    }
    // The search field is the page's control row: the first card follows a control's gap under it.
    Spacer(Modifier.height(RvSpace.controlGap))
    RvGutter {
        if (shown.isEmpty()) {
            RvEmptyCaption(stringResource(R.string.rv_conn_none_title), stringResource(R.string.rv_conn_none_line))
        } else {
            // A long press opens the page as a tap does, at the menus' 500 ms (the owner, N6).
            RvLongPressTiming { RvCard { shown.forEach { c -> RvConnectionRow(c, nowEpochMs) { routes.open(connections.indexOf(c)) } } } }
        }
    }
}

/** How many connections are open and what they moved each way, as 節點's line under its title. */
@Composable
private fun Summary(connections: List<RvConnection>) {
    val format = rememberRvFormat()
    val down = format.bytes(connections.sumOf { it.downBytes }).toString()
    val up = format.bytes(connections.sumOf { it.upBytes }).toString()
    RvText(
        pluralStringResource(R.plurals.rv_conn_summary, connections.size, connections.size, down, up),
        Rv.type.rowSub,
        Modifier.padding(start = RvSpace.gutter, end = RvSpace.gutter, bottom = RvSpace.subGap * 2),
        color = Rv.colors.textSecondary,
    )
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
        connection.app + SEPARATOR + connection.rule.targetName(),
        onClick = onClick,
        onLongClick = onClick,
        titleOverflow = TextOverflow.MiddleEllipsis,
    ) {
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(RvSpace.tight / 2)) {
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

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RvConnectionsPagePreview() {
    val dark = isSystemInDarkTheme()
    RvTheme(rvSystemScheme(dark), dark) { RvConnectionsPage(RvPolicyData.connections) }
}
