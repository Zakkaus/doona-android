package dev.doona.android.prototype.rv.subs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import dev.doona.android.R
import dev.doona.android.designsystem.theme.LocalFrameInsets
import dev.doona.android.prototype.rv.Rv
import dev.doona.android.prototype.rv.RvBanner
import dev.doona.android.prototype.rv.RvCard
import dev.doona.android.prototype.rv.RvChevron
import dev.doona.android.prototype.rv.RvConfirmSheet
import dev.doona.android.prototype.rv.RvGlassButton
import dev.doona.android.prototype.rv.RvGutter
import dev.doona.android.prototype.rv.RvIconDisc
import dev.doona.android.prototype.rv.RvLargeTitle
import dev.doona.android.prototype.rv.RvLongPressMenu
import dev.doona.android.prototype.rv.RvMenuItem
import dev.doona.android.prototype.rv.RvPrimaryButton
import dev.doona.android.prototype.rv.RvProgress
import dev.doona.android.prototype.rv.RvRow
import dev.doona.android.prototype.rv.RvScrollPage
import dev.doona.android.prototype.rv.RvSpace
import dev.doona.android.prototype.rv.RvText
import dev.doona.android.prototype.rv.data.RvData
import dev.doona.android.prototype.rv.data.RvSubscription
import dev.doona.android.prototype.rv.rememberRvFormat

/** What the subscriptions page shows: the subscriptions, the one whose last update failed, and the one asked to delete. */
@Immutable
internal data class RvSubsState(
    val subscriptions: List<RvSubscription> = RvData.subscriptions,
    val failedId: String? = null,
    val deletingId: String? = null,
)

/** Where the subscriptions page leads: back, a subscription's page, the add page; and what it asks for. */
internal class RvSubsActions(
    val back: () -> Unit = {},
    val open: (String) -> Unit = {},
    val add: () -> Unit = {},
    val update: (String) -> Unit = {},
    val updateAll: () -> Unit = {},
    val delete: (String?) -> Unit = {},
    val confirmDelete: (String) -> Unit = {},
)

/**
 * 訂閱: a pushed page with the large title and 更新所有訂閱 in the bar. A failed update shows the banner first, with 重試.
 * Each subscription is a card: its row (name, node count and last update, chevron to its page), then what it has used
 * of its quota and when it ends over the progress bar. A long press on a card opens 更新, 編輯 and 刪除; 刪除 asks in
 * the confirm sheet. 新增訂閱 floats at the bottom. A [notice] (the page's body while it loads) heads the cards; with
 * [noticeOnly] it stands in their place, and the title and the bar stay.
 */
@Composable
internal fun RvSubsPage(state: RvSubsState, actions: RvSubsActions, notice: (@Composable () -> Unit)? = null, noticeOnly: Boolean = false) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val title = stringResource(R.string.rv_subscriptions)
    RvScrollPage(
        title,
        insets,
        onBack = actions.back,
        actions = { RvGlassButton(it, R.drawable.rv_stats_ic_sync, stringResource(R.string.rv_stats_update_all), actions.updateAll) },
        bottomAction = { RvPrimaryButton(stringResource(R.string.rv_add_subscription), actions.add) },
    ) { titleBlock ->
        RvLargeTitle(title, titleBlock, insets)
        RvGutter {
            notice?.invoke()
            if (!noticeOnly) {
                state.subscriptions.firstOrNull { it.id == state.failedId }?.let { FailedBanner(it) { actions.update(it.id) } }
                state.subscriptions.forEach { SubscriptionCard(it, actions) }
            }
        }
    }
    val deleting = state.subscriptions.firstOrNull { it.id == state.deletingId }
    RvConfirmSheet(
        visible = deleting != null,
        title = stringResource(R.string.rv_sub_delete_title, deleting?.name.orEmpty()),
        body = pluralStringResource(R.plurals.rv_sub_delete_body, deleting?.nodes ?: 0, deleting?.nodes ?: 0),
        confirm = stringResource(R.string.rv_menu_delete),
        dismiss = stringResource(R.string.rv_cancel),
        onConfirm = { deleting?.let { actions.confirmDelete(it.id) } },
        onDismiss = { actions.delete(null) },
    )
}

/** The banner of a subscription whose last update failed: it keeps its last nodes, and 重試 updates it again. */
@Composable
internal fun FailedBanner(sub: RvSubscription, onRetry: () -> Unit) {
    RvBanner(
        stringResource(R.string.rv_sub_failed_title, sub.name),
        pluralStringResource(R.plurals.rv_sub_failed_reason, sub.nodes, sub.nodes),
        stringResource(R.string.rv_retry),
        onRetry,
    )
}

@Composable
private fun SubscriptionCard(sub: RvSubscription, actions: RvSubsActions) {
    val menu = rvSubscriptionMenu(onUpdate = { actions.update(sub.id) }, onEdit = { actions.open(sub.id) }, onDelete = { actions.delete(sub.id) })
    RvCard {
        RvLongPressMenu(menu, onClick = { actions.open(sub.id) }) { press ->
            Column(press) {
                RvRow({ RvIconDisc(R.drawable.rv_sym_rss_feed) }, sub.name, rvNodesUpdated(sub)) { RvChevron() }
                Usage(sub)
            }
        }
    }
}

/** Used of total at the start and the end date at the end, over the quota's bar (Revolut's goal card, #13). */
@Composable
private fun Usage(sub: RvSubscription) {
    val format = rememberRvFormat()
    val total = format.bytes(sub.totalBytes)
    val ends = sub.expiresAtEpochMs?.let { stringResource(R.string.rv_expires, format.expiry(it, RvData.nowEpochMs)) } ?: stringResource(R.string.rv_subs_no_expiry)
    Column(Modifier.padding(start = RvSpace.rowPadding, end = RvSpace.rowPadding, bottom = RvSpace.rowPadding)) {
        Row {
            RvText(stringResource(R.string.rv_subs_used, format.bytesIn(sub.usedBytes, total), total.toString()), Rv.type.rowSub, Modifier.weight(1f), color = Rv.colors.textSecondary)
            Spacer(Modifier.width(RvSpace.trailingGap))
            RvText(ends, Rv.type.rowSub, color = Rv.colors.textSecondary, align = TextAlign.End)
        }
        Spacer(Modifier.height(RvSpace.barGap))
        RvProgress(if (sub.totalBytes > 0) sub.usedBytes.toFloat() / sub.totalBytes else 0f)
    }
}

/** `32 個節點 · 06:00 更新`. */
@Composable
internal fun rvNodesUpdated(sub: RvSubscription): String =
    stringResource(R.string.rv_subs_nodes_updated, pluralStringResource(R.plurals.rv_nodes_nodes, sub.nodes, sub.nodes), rememberRvFormat().time(sub.updatedAtEpochMs))

/** A subscription's menu: 更新, 編輯, then 刪除 set apart, its glyph in the error hue. */
@Composable
internal fun rvSubscriptionMenu(onUpdate: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit): List<RvMenuItem> = listOf(
    RvMenuItem(stringResource(R.string.rv_menu_update), R.drawable.rv_stats_ic_sync, onClick = onUpdate),
    RvMenuItem(stringResource(R.string.rv_menu_edit), R.drawable.rv_sym_edit, onClick = onEdit),
    RvMenuItem(stringResource(R.string.rv_menu_delete), R.drawable.rv_sym_delete, destructive = true, separated = true, onClick = onDelete),
)
