package dev.doona.android.feature.nodes.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RV_NO_VALUE
import dev.doona.android.designsystem.rv.Rv
import dev.doona.android.designsystem.rv.RvIconDisc
import dev.doona.android.designsystem.rv.RvRow
import dev.doona.android.designsystem.rv.RvText
import dev.doona.android.designsystem.rv.rememberRvFormat
import dev.doona.android.feature.model.RvSubscription

/**
 * A subscription's row: a neutral disc, its name over its use against its quota and its end date as of [nowEpochMs], and
 * what is left.
 */
@Composable
internal fun RvSubscriptionRow(sub: RvSubscription, nowEpochMs: Long) {
    val format = rememberRvFormat()
    val total = format.bytes(sub.totalBytes)
    RvRow(
        { RvIconDisc(R.drawable.rv_sym_rss_feed) },
        sub.name,
        stringResource(R.string.rv_subscription_usage, format.usedOf(sub.usedBytes, total), total.toString(), sub.expiresAtEpochMs?.let { format.expiry(it, nowEpochMs) } ?: RV_NO_VALUE),
        titleKeepWords = false,
    ) { RvText(format.bytes(sub.leftBytes).toString(), Rv.type.rowValue, align = TextAlign.End) }
}

/** The 組態內 card's head row, laid out as a subscription's: the link disc, 組態內, and how many nodes were added by hand. */
@Composable
internal fun RvInlineRow(count: Int) {
    RvRow({ RvIconDisc(R.drawable.rv_sym_link) }, stringResource(R.string.rv_nodes_inline), pluralStringResource(R.plurals.rv_nodes_nodes, count, count))
}
