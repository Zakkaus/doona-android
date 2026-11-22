package dev.doona.android.prototype.rv.connections

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.tooling.preview.Preview
import dev.doona.android.R
import dev.doona.android.designsystem.theme.LocalFrameInsets
import dev.doona.android.prototype.rv.LocalRvNav
import dev.doona.android.prototype.rv.RV_NAV_CONNECTIONS
import dev.doona.android.prototype.rv.RV_NAV_FOUR
import dev.doona.android.prototype.rv.Rv
import dev.doona.android.prototype.rv.RvAppDisc
import dev.doona.android.prototype.rv.RvCard
import dev.doona.android.prototype.rv.RvFilterChip
import dev.doona.android.prototype.rv.RvFloatingNav
import dev.doona.android.prototype.rv.RvGlassButton
import dev.doona.android.prototype.rv.RvGutter
import dev.doona.android.prototype.rv.RvInline
import dev.doona.android.prototype.rv.RvLargeTitle
import dev.doona.android.prototype.rv.RvRow
import dev.doona.android.prototype.rv.RvScrollPage
import dev.doona.android.prototype.rv.RvSpace
import dev.doona.android.prototype.rv.RvText
import dev.doona.android.prototype.rv.RvTheme
import dev.doona.android.prototype.rv.data.RV_NOW
import dev.doona.android.prototype.rv.policy.RvConnection
import dev.doona.android.prototype.rv.policy.RvPolicyData
import dev.doona.android.prototype.rv.policy.targetName
import dev.doona.android.prototype.rv.rememberRvFormat
import dev.doona.android.prototype.rv.rvCollapsedOnScroll
import dev.doona.android.prototype.rv.rvNavClearance
import dev.doona.android.prototype.rv.rvSystemScheme

/** Where the 連線 tab leads: a connection's page, the two filters, search, and the other tabs. */
internal class RvConnectionsRoutes(
    val open: (Int) -> Unit = {},
    val filter: () -> Unit = {},
    val search: () -> Unit = {},
    val tab: (Int) -> Unit = {},
)

/**
 * 連線, the proposed fourth tab (features-gap Q5, option A), in the language of 節點: the large title with search in the
 * bar; a line counting the open connections and their bytes; the app and order chips; then one card of the connections
 * open now ([connections], newest first), each its app's icon, where it went and how long it has been open, over the
 * rule's group and its bytes down and up. A connection opens its page. The bar always has four tabs here. Host names are shown from
 * memory and never logged (D21).
 */
@Composable
internal fun RvConnectionsPage(connections: List<RvConnection>, routes: RvConnectionsRoutes = RvConnectionsRoutes(), nowEpochMs: Long = RV_NOW) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val scroll = rememberScrollState()
    val title = stringResource(R.string.rv_nav_connections)
    val search = stringResource(R.string.rv_conn_search)
    CompositionLocalProvider(LocalRvNav provides RV_NAV_FOUR) {
        RvScrollPage(
            title,
            insets,
            scroll = scroll,
            actions = { glass -> RvGlassButton(glass, R.drawable.rv_sym_search, search, routes.search) },
            overlay = { glass ->
                RvFloatingNav(RV_NAV_CONNECTIONS, routes.tab, glass, rvCollapsedOnScroll(scroll), insets, Modifier.align(Alignment.BottomCenter))
            },
        ) { titleBlock ->
            RvLargeTitle(title, titleBlock, insets)
            Summary(connections)
            Row(Modifier.padding(horizontal = RvSpace.gutter), verticalAlignment = Alignment.CenterVertically) {
                RvFilterChip(stringResource(R.string.rv_conn_all_apps), routes.filter)
                Spacer(Modifier.weight(1f))
                RvFilterChip(stringResource(R.string.rv_conn_newest), routes.filter)
            }
            Spacer(Modifier.height(RvSpace.titleToCard))
            RvGutter {
                RvCard { connections.forEachIndexed { i, c -> RvConnectionRow(c, nowEpochMs) { routes.open(i) } } }
            }
            Spacer(Modifier.height(rvNavClearance(insets)))
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
 * One connection: the app's own icon (the app is shown by it, as the brief asks, and named on its page); host:port over
 * the rule's group (or 直連); how long it has been open at the end of the first line and its bytes down and up at the
 * end of the second, so the host keeps most of its line. The icon's disc carries the app's name for a screen reader.
 */
@Composable
internal fun RvConnectionRow(connection: RvConnection, nowEpochMs: Long, onClick: () -> Unit) {
    val format = rememberRvFormat()
    val secondary = Rv.colors.textSecondary
    RvRow(
        { RvAppDisc(connection.packageName, connection.app) },
        connection.host + PORT + connection.port,
        connection.rule.targetName(),
        onClick = onClick,
        titleEnd = { RvText(rvAge((nowEpochMs - connection.startedAtEpochMs) / MS_PER_SECOND), Rv.type.rowValue, align = TextAlign.End) },
        subtitleEnd = {
            Row(horizontalArrangement = Arrangement.spacedBy(RvSpace.subGap)) {
                RvInline(R.drawable.rv_sym_arrow_downward, format.bytes(connection.downBytes).toString(), secondary, Rv.type.rowSub)
                RvInline(R.drawable.rv_sym_arrow_upward, format.bytes(connection.upBytes).toString(), secondary, Rv.type.rowSub)
            }
        },
    )
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
