package dev.doona.android.feature.subscriptions

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import dev.doona.android.R
import dev.doona.android.designsystem.rv.LocalFrameInsets
import dev.doona.android.designsystem.rv.LocalRvUpdating
import dev.doona.android.designsystem.rv.RV_NO_VALUE
import dev.doona.android.designsystem.rv.Rv
import dev.doona.android.designsystem.rv.RvBusy
import dev.doona.android.designsystem.rv.RvCard
import dev.doona.android.designsystem.rv.RvChevron
import dev.doona.android.designsystem.rv.RvConfirmSheet
import dev.doona.android.designsystem.rv.RvEmptyState
import dev.doona.android.designsystem.rv.RvGlass
import dev.doona.android.designsystem.rv.RvGlassButton
import dev.doona.android.designsystem.rv.RvGutter
import dev.doona.android.designsystem.rv.RvIconDisc
import dev.doona.android.designsystem.rv.RvLargeTitle
import dev.doona.android.designsystem.rv.RvLongPressMenu
import dev.doona.android.designsystem.rv.RvMenuItem
import dev.doona.android.designsystem.rv.RvNotice
import dev.doona.android.designsystem.rv.RvNoticeKind
import dev.doona.android.designsystem.rv.RvPrimaryButton
import dev.doona.android.designsystem.rv.RvProgress
import dev.doona.android.designsystem.rv.RvRow
import dev.doona.android.designsystem.rv.RvScrollPage
import dev.doona.android.designsystem.rv.RvSize
import dev.doona.android.designsystem.rv.RvSkeletonCard
import dev.doona.android.designsystem.rv.RvSkeletonCards
import dev.doona.android.designsystem.rv.RvSkeletonGoal
import dev.doona.android.designsystem.rv.RvSkeletonRow
import dev.doona.android.designsystem.rv.RvSkeletonText
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.designsystem.rv.RvText
import dev.doona.android.designsystem.rv.rememberRvFormat
import dev.doona.android.feature.model.RvSubscription

/**
 * What the subscriptions page shows: the subscriptions, now (for their end dates), the one whose last update failed, and
 * the one asked to delete.
 */
@Immutable
internal data class RvSubsState(
    val subscriptions: List<RvSubscription>,
    val nowEpochMs: Long,
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
 * the confirm sheet. 新增訂閱 floats at the bottom. With no subscription there is nothing to update or to float over: the
 * bar's 更新所有訂閱 and the floating 新增訂閱 give way to the empty state, which carries the one 新增訂閱. While [loading]
 * (before the engine is ready) the cards are skeletons of subscription cards ([SubsSkeleton]), and the cards fade in
 * over them in place; the title and the bar's actions are there from the start.
 */
@Composable
internal fun RvSubsPage(state: RvSubsState, actions: RvSubsActions, loading: Boolean = false) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val title = stringResource(R.string.rv_subscriptions)
    val empty = !loading && state.subscriptions.isEmpty()
    val updateAll: (@Composable (RvGlass) -> Unit)? =
        if (empty) null else ({ RvGlassButton(it, R.drawable.rv_stats_ic_sync, stringResource(R.string.rv_stats_update_all), actions.updateAll) })
    val add: (@Composable () -> Unit)? = if (empty) null else ({ RvPrimaryButton(stringResource(R.string.rv_add_subscription), actions.add) })
    // A failed update's banner waits for the cards: while they load it is not known yet.
    val failed = state.subscriptions.firstOrNull { it.id == state.failedId }?.takeUnless { loading }?.let { rvFailedBanner(it) { actions.update(it.id) } }
    RvScrollPage(title, insets, onBack = actions.back, actions = updateAll, bottomAction = add) { titleBlock ->
        RvLargeTitle(title, titleBlock, insets)
        RvGutter(banner = failed) {
            if (empty) {
                RvEmptyState(
                    stringResource(R.string.rv_empty_subscription),
                    stringResource(R.string.rv_empty_subscription_line),
                    stringResource(R.string.rv_add_subscription),
                    actions.add,
                )
            } else {
                RvSkeletonCards(loading, { SubsSkeleton(state.subscriptions) }) {
                    state.subscriptions.forEach { SubscriptionCard(it, actions, state.nowEpochMs) }
                }
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
        titleKeepWords = false,
    )
}

/** The banner of a subscription whose last update failed, an error: it keeps its last nodes, and 重試 updates it again. */
@Composable
internal fun rvFailedBanner(sub: RvSubscription, onRetry: () -> Unit) = RvNotice(
    RvNoticeKind.Error,
    stringResource(R.string.rv_sub_failed_title, sub.name),
    pluralStringResource(R.plurals.rv_sub_failed_reason, sub.nodes, sub.nodes),
    stringResource(R.string.rv_retry),
    onRetry,
    titleKeepWords = false,
)

/** A subscription: its row, with a busy bar before the chevron while it updates ([LocalRvUpdating], as the node picker's row), then its use. */
@Composable
private fun SubscriptionCard(sub: RvSubscription, actions: RvSubsActions, nowEpochMs: Long) {
    val menu = rvSubscriptionMenu(onUpdate = { actions.update(sub.id) }, onEdit = { actions.open(sub.id) }, onDelete = { actions.delete(sub.id) })
    val updating = sub.id in LocalRvUpdating.current
    RvCard {
        RvLongPressMenu(menu, onClick = { actions.open(sub.id) }) { press ->
            Column(press) {
                RvRow({ RvIconDisc(R.drawable.rv_sym_rss_feed) }, sub.name, rvNodesUpdated(sub), titleKeepWords = false) {
                    if (updating) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RvBusy(Modifier.width(RvSize.busy))
                            Spacer(Modifier.width(RvSpace.trailingGap))
                            RvChevron()
                        }
                    } else {
                        RvChevron()
                    }
                }
                Usage(sub, nowEpochMs)
            }
        }
    }
}

/**
 * 訂閱's cards while it loads: subscription cards, each its row (the name over its nodes and update, the chevron's room)
 * and its quota's goal. The names of the [subscriptions] are known before the engine is ready and take their lines, so a
 * name that wraps keeps its row's height; the figures are bars.
 */
@Composable
private fun SubsSkeleton(subscriptions: List<RvSubscription>) {
    repeat(SKELETON_CARDS) {
        RvSkeletonCard {
            RvSkeletonRow(it, RvSkeletonText.Mixed, chevron = true, title = subscriptions.getOrNull(it)?.name, titleKeepWords = false)
            RvSkeletonGoal()
        }
    }
}

/** The subscription cards the skeleton shows: two, as Revolut's list skeleton shows two rows (revolut-UX §11). */
private const val SKELETON_CARDS = 2

/**
 * Used of total at the start and the end date as of [nowEpochMs] at the end, over the quota's bar (Revolut's goal card,
 * #13). With no quota the start reads — and there is no bar, as on the subscription's page.
 */
@Composable
private fun Usage(sub: RvSubscription, nowEpochMs: Long) {
    val format = rememberRvFormat()
    val total = format.bytes(sub.totalBytes)
    val ends = sub.expiresAtEpochMs?.let { stringResource(R.string.rv_expires, format.expiry(it, nowEpochMs)) } ?: stringResource(R.string.rv_subs_no_expiry)
    Column(Modifier.padding(start = RvSpace.rowPadding, end = RvSpace.rowPadding, bottom = RvSpace.rowPadding)) {
        Row {
            val used = if (sub.hasQuota) stringResource(R.string.rv_subs_used, format.usedOf(sub.usedBytes, total), total.toString()) else RV_NO_VALUE
            RvText(used, Rv.type.rowSub, Modifier.weight(1f), color = Rv.colors.textSecondary)
            Spacer(Modifier.width(RvSpace.trailingGap))
            RvText(ends, Rv.type.rowSub, color = Rv.colors.textSecondary, align = TextAlign.End)
        }
        if (sub.hasQuota) {
            Spacer(Modifier.height(RvSpace.barGap))
            RvProgress(sub.usedBytes.toFloat() / sub.totalBytes)
        }
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
