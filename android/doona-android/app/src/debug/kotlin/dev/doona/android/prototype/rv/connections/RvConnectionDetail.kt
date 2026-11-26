package dev.doona.android.prototype.rv.connections

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import dev.doona.android.R
import dev.doona.android.designsystem.rv.LocalFrameInsets
import dev.doona.android.designsystem.rv.Rv
import dev.doona.android.designsystem.rv.RvAddRow
import dev.doona.android.designsystem.rv.RvAppDisc
import dev.doona.android.designsystem.rv.RvBarSpacer
import dev.doona.android.designsystem.rv.RvCard
import dev.doona.android.designsystem.rv.RvChrome
import dev.doona.android.designsystem.rv.RvFlag
import dev.doona.android.designsystem.rv.RvGutter
import dev.doona.android.designsystem.rv.RvHero
import dev.doona.android.designsystem.rv.RvIconDisc
import dev.doona.android.designsystem.rv.RvPart
import dev.doona.android.designsystem.rv.RvRow
import dev.doona.android.designsystem.rv.RvScrollPage
import dev.doona.android.designsystem.rv.RvSectionTitle
import dev.doona.android.designsystem.rv.RvSize
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.designsystem.rv.RvStatRow
import dev.doona.android.designsystem.rv.RvText
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.designsystem.rv.SEPARATOR
import dev.doona.android.designsystem.rv.rememberRvFormat
import dev.doona.android.designsystem.rv.rvSystemScheme
import dev.doona.android.feature.model.text
import dev.doona.android.feature.policies.kind
import dev.doona.android.feature.policies.title
import dev.doona.android.prototype.rv.data.RV_NOW
import dev.doona.android.prototype.rv.policy.RvConnection
import dev.doona.android.prototype.rv.policy.RvPolicyData

/**
 * A connection's page, after Revolut's transaction detail (revolut-ux3 §1, "For doona"): the back bar of every pushed
 * page; a centred header that starts where every page's large title or mark starts ([RvChrome.largeTitleTop] under the
 * bar's controls): the app's disc, its name (which the bar takes over as the header scrolls under it), the bytes it
 * moved as the hero figure, and where it went, the whole host and port, wrapped; then its key-value card (TCP or UDP,
 * when it started, how long it has run, its bytes each way); its path from the app through the rule and the group to
 * the node, one row each as the other pages draw them (直連 ends at the arrow); 新增規則 alone in its card, the row 規則
 * draws for it, which opens the quick rule from this connection; and 關閉連線 alone in the last card with its glyph in
 * the error hue, as 訂閱's 刪除訂閱. The bar holds only the back disc: nothing on the page belongs in a menu.
 */
@Composable
internal fun RvConnectionDetail(
    connection: RvConnection,
    onBack: () -> Unit,
    onClose: () -> Unit,
    onNewRule: () -> Unit,
    nowEpochMs: Long = RV_NOW,
) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    RvScrollPage(connection.app, insets, onBack = onBack) { titleBlock ->
        Header(connection, titleBlock, insets)
        RvGutter { StatsCard(connection, nowEpochMs) }
        RvSectionTitle(stringResource(R.string.rv_conn_path), Modifier.padding(horizontal = RvSpace.gutter))
        RvGutter {
            PathCard(connection)
            RvCard { RvAddRow(stringResource(R.string.rv_policy_new_rule), onNewRule, afterRemoves = false) }
            RvCard { RvRow({ RvIconDisc(R.drawable.rv_sym_close, Rv.colors.error) }, stringResource(R.string.rv_conn_close), null, onClick = onClose) }
        }
    }
}

/**
 * The centred header: the app's disc at [RvSize.headerDisc], the size of node detail's flag, [RvSpace.discToLabel] over
 * its name, as a round action sets its label; the bytes moved both ways as [RvHero]; the host and port in secondary
 * text, breaking only after a dot of the domain, and for a connection the core had no name for, that only an address
 * rule could match it. The first card follows [RvSpace.headerGap] under it, as under every page header.
 */
@Composable
private fun Header(connection: RvConnection, titleBlock: Modifier, insets: WindowInsets) {
    val format = rememberRvFormat()
    val secondary = Rv.colors.textSecondary
    // A break may follow each dot of a domain, so a long one wraps between its labels, not inside one.
    val host = if (connection.hasDomain) connection.host.replace(DOT, DOT + BREAK) else connection.host
    RvBarSpacer(insets)
    Spacer(Modifier.height(RvChrome.largeTitleTop))
    // One block of the page's column, which RvLayoutTest measures from where a large title starts.
    Column(Modifier.fillMaxWidth().testTag(RvPart.BLOCK).padding(horizontal = RvSpace.gutter), horizontalAlignment = Alignment.CenterHorizontally) {
        RvAppDisc(connection.packageName, size = RvSize.headerDisc)
        Spacer(Modifier.height(RvSpace.discToLabel))
        RvText(connection.app, Rv.type.rowTitle, titleBlock.semantics { heading() }, align = TextAlign.Center)
        RvHero(format.bytes(connection.downBytes + connection.upBytes))
        RvText(host + PORT + connection.port, Rv.type.rowSub, color = secondary, align = TextAlign.Center, maxLines = Int.MAX_VALUE, keepWords = false)
        if (!connection.hasDomain) RvText(stringResource(R.string.rv_conn_no_domain), Rv.type.rowSub, color = secondary, align = TextAlign.Center)
    }
    Spacer(Modifier.height(RvSpace.headerGap))
}

/** Key stats: TCP or UDP, when it started and for how long, and its bytes each way. */
@Composable
private fun StatsCard(connection: RvConnection, nowEpochMs: Long) {
    val format = rememberRvFormat()
    RvCard {
        RvStatRow(stringResource(R.string.rv_nodes_protocol), if (connection.udp) UDP else TCP)
        RvStatRow(stringResource(R.string.rv_conn_started), format.clock(connection.startedAtEpochMs))
        RvStatRow(stringResource(R.string.rv_conn_age), rvAge((nowEpochMs - connection.startedAtEpochMs) / MS_PER_SECOND))
        RvStatRow(stringResource(R.string.rv_downloaded), format.bytes(connection.downBytes).toString())
        RvStatRow(stringResource(R.string.rv_uploaded), format.bytes(connection.upBytes).toString())
    }
}

/**
 * App, rule, group, node: the chain FlClash draws on a connection card, here one row a step. The rule says where it
 * stands in the list.
 */
@Composable
private fun PathCard(connection: RvConnection) {
    val rule = connection.rule
    val index = RvPolicyData.allRules.indexOf(rule) + 1
    val ruleLine = stringResource(R.string.rv_conn_rule_index, index) + SEPARATOR + rule.match.kind()
    RvCard {
        RvRow({ RvAppDisc(connection.packageName) }, connection.app, connection.packageName, titleKeepWords = false, subtitleKeepWords = false)
        RvRow({ RvIconDisc(R.drawable.rv_sym_rule) }, rule.match.title(), ruleLine, titleKeepWords = false)
        val group = connection.group
        val node = connection.node
        if (group == null || node == null) {
            RvRow({ RvIconDisc(R.drawable.rv_sym_arrow_right_alt) }, stringResource(R.string.mode_direct), stringResource(R.string.rv_policy_no_proxy))
        } else {
            RvRow({ RvIconDisc(group.kind.glyph) }, group.name.text(), stringResource(group.kind.label), titleKeepWords = false)
            RvRow({ RvFlag(node.place.flag, RvSize.rowDisc) }, node.name, listOf(node.protocol, stringResource(node.place.label)).joinToString(SEPARATOR), titleKeepWords = false)
        }
    }
}

private const val TCP = "TCP"
private const val DOT = "."
private const val BREAK = "\u200B"
private const val UDP = "UDP"

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RvConnectionDetailPreview() {
    val dark = isSystemInDarkTheme()
    RvTheme(rvSystemScheme(dark), dark) { RvConnectionDetail(RvPolicyData.connections[3], onBack = {}, onClose = {}, onNewRule = {}) }
}
