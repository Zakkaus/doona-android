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
import dev.doona.android.designsystem.rv.LocalFrameInsets
import dev.doona.android.designsystem.rv.RvAppDisc
import dev.doona.android.designsystem.rv.RvBanner
import dev.doona.android.designsystem.rv.RvCard
import dev.doona.android.designsystem.rv.RvCheckRow
import dev.doona.android.designsystem.rv.RvConfirmSheet
import dev.doona.android.designsystem.rv.RvEmptyCaption
import dev.doona.android.designsystem.rv.RvEmptyState
import dev.doona.android.designsystem.rv.RvGutter
import dev.doona.android.designsystem.rv.RvIconDisc
import dev.doona.android.designsystem.rv.RvLargeTitle
import dev.doona.android.designsystem.rv.RvLongPressMenu
import dev.doona.android.designsystem.rv.RvPrimaryButton
import dev.doona.android.designsystem.rv.RvRow
import dev.doona.android.designsystem.rv.RvScrollPage
import dev.doona.android.designsystem.rv.RvSectionTitle
import dev.doona.android.designsystem.rv.RvSkeletonCard
import dev.doona.android.designsystem.rv.RvSkeletonControlCard
import dev.doona.android.designsystem.rv.RvSkeletonGoal
import dev.doona.android.designsystem.rv.RvSkeletonHeader
import dev.doona.android.designsystem.rv.RvSkeletonRow
import dev.doona.android.designsystem.rv.RvSkeletonSeeAll
import dev.doona.android.designsystem.rv.RvSkeletonTiles
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.designsystem.rv.RvTextField
import dev.doona.android.prototype.rv.data.RvData
import dev.doona.android.prototype.rv.data.RvSubscription
import dev.doona.android.prototype.rv.settings.RvPerAppData
import dev.doona.android.prototype.rv.states.RvActivityNotice
import dev.doona.android.prototype.rv.states.rvActivityBanner
import dev.doona.android.prototype.rv.subs.rvFailedBanner
import dev.doona.android.prototype.rv.subs.rvSubscriptionMenu

/**
 * The library's new parts in one page, for the owner to try on a phone (route `gallery`): the text field in its states,
 * subscription rows whose long press opens the menu, app rows to check, the banner in its three kinds, the empty state,
 * the loading skeleton, the source field and its diff, and the confirm sheet behind a button. The goldens draw the same
 * parts.
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
        Section(R.string.rv_gallery_check) { RvGalleryChecks() }
        Section(R.string.rv_gallery_banner) { RvGalleryBanners() }
        Section(R.string.rv_gallery_empty) { RvGalleryEmpty() }
        Section(R.string.rv_gallery_skeleton) { RvGallerySkeleton() }
        Section(R.string.rv_gallery_code) { RvGalleryCode() }
        Section(R.string.rv_gallery_source) { RvGallerySource() }
        Section(R.string.rv_gallery_diff) { RvGalleryDiff() }
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
    Column(verticalArrangement = Arrangement.spacedBy(RvSpace.chartGap)) {
        RvTextField("", {}, link, placeholder = RV_LINK_PLACEHOLDER)
        RvTextField(RV_SAMPLE_LINK, {}, link)
        RvTextField(RV_SAMPLE_LINK, {}, link, error = stringResource(R.string.rv_add_not_subscription))
        RvTextField(RvData.subscriptions.first().name, {}, stringResource(R.string.rv_add_name), enabled = false)
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

/** Three apps as 依 App lists them, the first and the last checked; a tap on a row checks or unchecks it. */
@Composable
internal fun RvGalleryChecks() {
    val apps = RvPerAppData.apps.take(3)
    var checked by remember { mutableStateOf(setOf(apps.first().packageName, apps.last().packageName)) }
    RvCard {
        apps.forEach { app ->
            val on = app.packageName in checked
            RvCheckRow({ RvAppDisc(app.packageName) }, app.label, app.packageName, on, { checked = if (it) checked + app.packageName else checked - app.packageName })
        }
    }
}

/** The banner in each of its kinds: a subscription that did not update (error), no network (warning), a crash's log (info). */
@Composable
internal fun RvGalleryBanners() {
    val sub = RvData.subscriptions.first()
    Column(verticalArrangement = Arrangement.spacedBy(RvSpace.cardGap)) {
        RvBanner(rvFailedBanner(sub) {})
        listOf(RvActivityNotice.Offline, RvActivityNotice.Crash).forEach { notice -> rvActivityBanner(notice) {}?.let { RvBanner(it) } }
    }
}

/** The two empty states: no subscription yet, with 新增訂閱; then a card with nothing in it, 連線 with the tunnel off. */
@Composable
internal fun RvGalleryEmpty(onAdd: () -> Unit = {}) {
    Column(verticalArrangement = Arrangement.spacedBy(RvSpace.cardGap)) {
        RvEmptyState(
            stringResource(R.string.rv_empty_subscription),
            stringResource(R.string.rv_empty_subscription_line),
            stringResource(R.string.rv_add_subscription),
            onAdd,
        )
        RvEmptyCaption(stringResource(R.string.rv_conn_off_title), stringResource(R.string.rv_conn_off_line))
    }
}

/**
 * The skeleton's parts as a page shows them while it loads: a control card, a card with its header, a row without an
 * end, two rows with one and its see-all line, the tiles, and a card with a goal under its row.
 */
@Composable
internal fun RvGallerySkeleton() {
    Column(verticalArrangement = Arrangement.spacedBy(RvSpace.cardGap)) {
        RvSkeletonControlCard()
        RvSkeletonCard {
            RvSkeletonHeader()
            RvSkeletonRow(0)
            RvSkeletonRow(1, end = true)
            RvSkeletonRow(2, end = true)
            RvSkeletonSeeAll()
        }
        RvSkeletonTiles()
        RvSkeletonCard {
            RvSkeletonRow(3)
            RvSkeletonGoal()
        }
    }
}

/** An invented subscription link (a reserved `.example` host) and the field's placeholder. */
internal const val RV_SAMPLE_LINK = "https://sub.harbor-cloud.example/api/v1/client/subscribe?token=8f3a6c"
internal const val RV_LINK_PLACEHOLDER = "https://"
