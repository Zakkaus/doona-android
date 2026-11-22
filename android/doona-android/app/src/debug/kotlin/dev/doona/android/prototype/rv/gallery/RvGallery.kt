package dev.doona.android.prototype.rv.gallery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.doona.android.R
import dev.doona.android.designsystem.theme.LocalFrameInsets
import dev.doona.android.prototype.rv.RvBanner
import dev.doona.android.prototype.rv.RvCard
import dev.doona.android.prototype.rv.RvConfirmSheet
import dev.doona.android.prototype.rv.RvEmptyState
import dev.doona.android.prototype.rv.RvGutter
import dev.doona.android.prototype.rv.RvIconDisc
import dev.doona.android.prototype.rv.RvLargeTitle
import dev.doona.android.prototype.rv.RvLongPressMenu
import dev.doona.android.prototype.rv.RvMenuItem
import dev.doona.android.prototype.rv.RvPrimaryButton
import dev.doona.android.prototype.rv.RvRow
import dev.doona.android.prototype.rv.RvScrollPage
import dev.doona.android.prototype.rv.RvSectionTitle
import dev.doona.android.prototype.rv.RvSpace
import dev.doona.android.prototype.rv.RvTextField
import dev.doona.android.prototype.rv.data.RvData
import dev.doona.android.prototype.rv.data.RvSubscription

/**
 * The library's new parts in one page, for the owner to try on a phone (route `gallery`): the text field in its states,
 * subscription rows whose long press opens the menu, the error banner, the empty state, and the confirm sheet behind a
 * button. The goldens draw the same parts.
 */
@Composable
internal fun RvGalleryScreen(onBack: () -> Unit) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    var deleting by remember { mutableStateOf<RvSubscription?>(null) }
    val title = stringResource(R.string.rv_gallery_title)
    RvScrollPage(title, insets, onBack = onBack) { titleBlock ->
        RvLargeTitle(title, titleBlock, insets)
        Section(R.string.rv_gallery_fields) { RvGalleryFields() }
        Section(R.string.rv_gallery_menu) { RvGalleryMenuCard(onDelete = { deleting = it }) }
        Section(R.string.rv_gallery_banner) { RvGalleryBanners() }
        Section(R.string.rv_gallery_empty) { RvGalleryEmpty() }
        Section(R.string.rv_gallery_sheet) { RvPrimaryButton(stringResource(R.string.rv_menu_delete), { deleting = RvData.subscriptions.first() }) }
    }
    val sub = deleting
    RvConfirmSheet(
        visible = sub != null,
        title = stringResource(R.string.rv_sub_delete_title, sub?.name.orEmpty()),
        body = pluralStringResource(R.plurals.rv_sub_delete_body, sub?.nodes ?: 0, sub?.nodes ?: 0),
        confirm = stringResource(R.string.rv_menu_delete),
        dismiss = stringResource(R.string.rv_cancel),
        onConfirm = { deleting = null },
        onDismiss = { deleting = null },
    )
}

@Composable
private fun Section(title: Int, content: @Composable () -> Unit) {
    RvSectionTitle(stringResource(title), Modifier.padding(horizontal = RvSpace.gutter))
    RvGutter { content() }
}

/** The text field empty with its placeholder, holding a link, refusing it, and disabled. */
@Composable
internal fun RvGalleryFields() {
    val link = stringResource(R.string.rv_add_link)
    val hint = stringResource(R.string.rv_add_link_hint)
    Column(verticalArrangement = Arrangement.spacedBy(RvSpace.chartGap)) {
        RvTextField("", {}, link, placeholder = RV_LINK_PLACEHOLDER, supporting = hint)
        RvTextField(RV_SAMPLE_LINK, {}, link, supporting = hint)
        RvTextField(RV_SAMPLE_LINK, {}, link, error = stringResource(R.string.rv_add_not_subscription))
        RvTextField(RvData.subscriptions.first().name, {}, stringResource(R.string.rv_add_name), supporting = stringResource(R.string.rv_add_name_hint), enabled = false)
    }
}

/** The subscriptions as rows; a long press on one opens 更新, 編輯 and 刪除. */
@Composable
internal fun RvGalleryMenuCard(onDelete: (RvSubscription) -> Unit = {}) {
    RvCard {
        RvData.subscriptions.forEach { sub ->
            RvLongPressMenu(rvSubscriptionMenu(onUpdate = {}, onEdit = {}, onDelete = { onDelete(sub) }), onClick = {}) { press ->
                RvRow({ RvIconDisc(R.drawable.rv_sym_rss_feed) }, sub.name, pluralStringResource(R.plurals.rv_nodes_nodes, sub.nodes, sub.nodes), press)
            }
        }
    }
}

/** A subscription's menu: 更新, 編輯, then 刪除 set apart, its glyph in the error hue. */
@Composable
internal fun rvSubscriptionMenu(onUpdate: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit): List<RvMenuItem> = listOf(
    RvMenuItem(stringResource(R.string.rv_menu_update), R.drawable.rv_stats_ic_sync, onClick = onUpdate),
    RvMenuItem(stringResource(R.string.rv_menu_edit), R.drawable.rv_sym_edit, onClick = onEdit),
    RvMenuItem(stringResource(R.string.rv_menu_delete), R.drawable.rv_sym_delete, destructive = true, separated = true, onClick = onDelete),
)

/** The banner for a subscription that did not update, for no network, and for a VPN another app took. */
@Composable
internal fun RvGalleryBanners() {
    val sub = RvData.subscriptions.first()
    Column(verticalArrangement = Arrangement.spacedBy(RvSpace.cardGap)) {
        RvBanner(
            stringResource(R.string.rv_sub_failed_title, sub.name),
            pluralStringResource(R.plurals.rv_sub_failed_reason, sub.nodes, sub.nodes),
            stringResource(R.string.rv_retry),
            {},
        )
        RvBanner(stringResource(R.string.rv_offline), stringResource(R.string.rv_offline_reason), stringResource(R.string.rv_network_settings), {}, icon = R.drawable.rv_sym_wifi_off)
        RvBanner(stringResource(R.string.rv_vpn_taken), stringResource(R.string.rv_vpn_taken_reason), stringResource(R.string.rv_reconnect), {}, icon = R.drawable.rv_sym_power)
    }
}

/** No subscription yet, with 新增訂閱. */
@Composable
internal fun RvGalleryEmpty(onAdd: () -> Unit = {}) {
    RvEmptyState(
        R.drawable.rv_sym_rss_feed,
        stringResource(R.string.rv_empty_subscription),
        stringResource(R.string.rv_empty_subscription_line),
        stringResource(R.string.rv_add_subscription),
        onAdd,
    )
}

/** An invented subscription link (a reserved `.example` host) and the field's placeholder. */
internal const val RV_SAMPLE_LINK = "https://sub.harbor-cloud.example/api/v1/client/subscribe?token=8f3a6c"
internal const val RV_LINK_PLACEHOLDER = "https://"
