package dev.doona.android.prototype.rv.connections

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.designsystem.rv.rvSystemScheme
import dev.doona.android.feature.connections.RvConnectionGroup
import dev.doona.android.feature.connections.RvConnectionNode
import dev.doona.android.feature.model.text
import dev.doona.android.prototype.rv.data.RV_NOW
import dev.doona.android.prototype.rv.policy.RvConnection
import dev.doona.android.prototype.rv.policy.RvPolicyData

@Composable
internal fun RvConnectionDetail(
    connection: RvConnection,
    onBack: () -> Unit,
    onClose: () -> Unit,
    onNewRule: () -> Unit,
    nowEpochMs: Long = RV_NOW,
) {
    val detail = with(connection) {
        dev.doona.android.feature.connections.RvConnection(
            host, host, port, hasDomain, app, packageName, udp, downBytes, upBytes, startedAtEpochMs,
            rule.match, RvPolicyData.allRules.indexOf(rule) + 1,
            group?.let { RvConnectionGroup(it.name.text(), it.kind) },
            node?.let { RvConnectionNode(it.name, it.protocol, it.place) }, rule.end.takeIf { group == null },
        )
    }
    dev.doona.android.feature.connections.RvConnectionDetail(detail, onBack, onClose, onNewRule, nowEpochMs)
}

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RvConnectionDetailPreview() {
    val dark = isSystemInDarkTheme()
    RvTheme(rvSystemScheme(dark), dark) { RvConnectionDetail(RvPolicyData.connections[3], onBack = {}, onClose = {}, onNewRule = {}) }
}
