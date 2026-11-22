package dev.doona.android.prototype.rv.connections

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import dev.doona.android.R
import dev.doona.android.designsystem.theme.LocalFrameInsets
import dev.doona.android.prototype.rv.RvAppDisc
import dev.doona.android.prototype.rv.RvBarSpacer
import dev.doona.android.prototype.rv.RvCard
import dev.doona.android.prototype.rv.RvFlag
import dev.doona.android.prototype.rv.RvGutter
import dev.doona.android.prototype.rv.RvIconDisc
import dev.doona.android.prototype.rv.RvPrimaryButton
import dev.doona.android.prototype.rv.RvRow
import dev.doona.android.prototype.rv.RvScrollPage
import dev.doona.android.prototype.rv.RvSectionTitle
import dev.doona.android.prototype.rv.RvSize
import dev.doona.android.prototype.rv.RvSpace
import dev.doona.android.prototype.rv.RvStatRow
import dev.doona.android.prototype.rv.RvTheme
import dev.doona.android.prototype.rv.data.RV_NOW
import dev.doona.android.prototype.rv.nodes.SEPARATOR
import dev.doona.android.prototype.rv.policy.RvConnection
import dev.doona.android.prototype.rv.policy.RvPolicyData
import dev.doona.android.prototype.rv.policy.kind
import dev.doona.android.prototype.rv.policy.title
import dev.doona.android.prototype.rv.rememberRvFormat
import dev.doona.android.prototype.rv.rvSystemScheme

/**
 * A connection's page, in node detail's grammar: the app's name as the compact title; where it went (host, port, TCP or
 * UDP); key stats (when it started, how long it has run, its bytes each way); its path from the app through
 * the rule and the group to the node, one row each as the other pages draw them (直連 ends at the arrow); 關閉連線; and
 * 新增規則 floating at the bottom, which opens the quick rule from this connection.
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
    RvScrollPage(
        connection.app,
        insets,
        onBack = onBack,
        compact = true,
        bottomAction = { RvPrimaryButton(stringResource(R.string.rv_policy_new_rule), onNewRule) },
    ) {
        RvBarSpacer(insets)
        Spacer(Modifier.height(RvSpace.gutter))
        RvGutter {
            TargetCard(connection)
            StatsCard(connection, nowEpochMs)
        }
        RvSectionTitle(stringResource(R.string.rv_conn_path), Modifier.padding(horizontal = RvSpace.gutter))
        RvGutter {
            PathCard(connection)
            RvCard { RvRow({ RvIconDisc(R.drawable.rv_sym_close) }, stringResource(R.string.rv_conn_close), null, onClick = onClose) }
        }
    }
}

/**
 * Where it went, as a row, because a host name does not fit a key-stats value: the host over its port and TCP or UDP,
 * and, for a connection the core had no name for, that only an address rule could match it.
 */
@Composable
private fun TargetCard(connection: RvConnection) {
    val line = buildList {
        add(stringResource(R.string.rv_conn_port, connection.port))
        add(if (connection.udp) UDP else TCP)
        if (!connection.hasDomain) add(stringResource(R.string.rv_conn_no_domain))
    }.joinToString(SEPARATOR)
    RvCard { RvRow({ RvIconDisc(R.drawable.rv_sym_dns) }, connection.host, line) }
}

@Composable
private fun StatsCard(connection: RvConnection, nowEpochMs: Long) {
    val format = rememberRvFormat()
    RvCard {
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
        RvRow({ RvAppDisc(connection.packageName) }, connection.app, connection.packageName)
        RvRow({ RvIconDisc(R.drawable.rv_sym_rule) }, rule.match.title(), ruleLine)
        val group = connection.group
        val node = connection.node
        if (group == null || node == null) {
            RvRow({ RvIconDisc(R.drawable.rv_sym_arrow_right_alt) }, stringResource(R.string.mode_direct), stringResource(R.string.rv_policy_no_proxy))
        } else {
            RvRow({ RvIconDisc(group.kind.glyph) }, stringResource(group.name), stringResource(group.kind.label))
            RvRow({ RvFlag(node.place.name, RvSize.rowDisc) }, node.name, listOf(node.protocol, stringResource(node.place.label)).joinToString(SEPARATOR))
        }
    }
}

private const val TCP = "TCP"
private const val UDP = "UDP"

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RvConnectionDetailPreview() {
    val dark = isSystemInDarkTheme()
    RvTheme(rvSystemScheme(dark), dark) { RvConnectionDetail(RvPolicyData.connections[3], onBack = {}, onClose = {}, onNewRule = {}) }
}
