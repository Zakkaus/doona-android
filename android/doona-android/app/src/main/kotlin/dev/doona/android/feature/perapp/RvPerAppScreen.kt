package dev.doona.android.feature.perapp

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.doona.android.R
import dev.doona.android.designsystem.rv.LocalFrameInsets
import dev.doona.android.designsystem.rv.RvAppDisc
import dev.doona.android.designsystem.rv.RvBannerSlot
import dev.doona.android.designsystem.rv.RvBusy
import dev.doona.android.designsystem.rv.RvCard
import dev.doona.android.designsystem.rv.RvCheckRow
import dev.doona.android.designsystem.rv.RvEmptyCaption
import dev.doona.android.designsystem.rv.RvFieldMessage
import dev.doona.android.designsystem.rv.RvIconDisc
import dev.doona.android.designsystem.rv.RvLargeTitle
import dev.doona.android.designsystem.rv.RvNotice
import dev.doona.android.designsystem.rv.RvNoticeKind
import dev.doona.android.designsystem.rv.RvPart
import dev.doona.android.designsystem.rv.RvPrimaryButton
import dev.doona.android.designsystem.rv.RvRow
import dev.doona.android.designsystem.rv.RvScrollPage
import dev.doona.android.designsystem.rv.RvSearchField
import dev.doona.android.designsystem.rv.RvSearchSlot
import dev.doona.android.designsystem.rv.RvSegmented
import dev.doona.android.designsystem.rv.RvSettingsCard
import dev.doona.android.designsystem.rv.RvSettingsRadioRow
import dev.doona.android.designsystem.rv.RvSize
import dev.doona.android.designsystem.rv.RvSkeletonCard
import dev.doona.android.designsystem.rv.RvSkeletonFade
import dev.doona.android.designsystem.rv.RvSkeletonRow
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.designsystem.rv.RvSwitch
import dev.doona.android.designsystem.rv.rvRowToggle
import dev.doona.android.designsystem.rv.rvSkeletonRowsToFill
import dev.doona.android.feature.model.RvAppPolicy

/** What the 依 App page does: pick a policy, check an app, search, save, and rebuild the tunnel after a failed one. */
internal class RvPerAppActions(
    val policy: (RvAppPolicy) -> Unit = {},
    val toggle: (String, Boolean) -> Unit = { _, _ -> },
    val query: (String) -> Unit = {},
    val save: () -> Unit = {},
    val reconnect: () -> Unit = {},
    val editList: () -> Unit = {},
    val reload: () -> Unit = {},
)

/**
 * The page's layouts for the owner to pick from: [Today] is PV-a's, the policies' card and a switch per app; [Checks]
 * (`perapp-v1`) keeps the card and checks each app with [RvCheckRow]; [Segmented] (`perapp-v2`) puts the policies in
 * 策略's segmented control under the title, and checks each app with [RvCheckRow].
 */
internal enum class RvPerAppLook { Today, Checks, Segmented }

/**
 * 依 App, a page of 設定, in DNS's language: the large title under the back bar, then one card of the three policies as
 * radio rows. Under 只有勾選的 and 勾選的以外 a card lists the phone's apps, each its icon, name and package with a switch,
 * and the search field ([RvSearchField], the node picker's, in [RvSearchSlot]) stands over that card; a search
 * that matches nothing says so in the library's empty caption. 只有勾選的 with nothing checked says what it needs under the policies, as a field says
 * its error ([RvFieldMessage]), and cannot be saved. 儲存 floats at the foot, enabled only when the edits can be saved, since a save rebuilds
 * the tunnel. A save under way, a failed one and a tunnel that would not come back after one show at the top: a busy
 * row, or the library's notice card with 重試. While the apps are read, their card is a skeleton of app rows, and the
 * apps fade in over it in place.
 */
@Composable
internal fun RvPerAppScreen(state: RvPerAppState, actions: RvPerAppActions, onBack: () -> Unit, look: RvPerAppLook = RvPerAppLook.Today) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val title = stringResource(R.string.rv_settings_per_app)
    val listed = state.edited.policy != RvAppPolicy.All
    val hint = stringResource(R.string.perapp_search)
    RvScrollPage(
        title,
        insets,
        onBack = onBack,
        bottomAction = { RvPrimaryButton(stringResource(R.string.perapp_save), actions.save, enabled = state.canSave) },
    ) { titleBlock ->
        RvLargeTitle(title, titleBlock, insets)
        RvBannerSlot(failure(state, actions))
        Column(Modifier.fillMaxWidth().padding(horizontal = RvSpace.gutter)) {
            if (state.save == RvSave.Saving) {
                RvCard { BusyRow(stringResource(R.string.rv_perapp_saving)) }
                Spacer(Modifier.height(RvSpace.cardGap))
            }
            // The policies and the error under them are one block of the column, as a field and its message are.
            Column(Modifier.testTag(RvPart.BLOCK)) {
                Policies(state, actions, look)
                if (state.needsApp) RvFieldMessage(stringResource(R.string.rv_perapp_needs_app))
            }
            if (listed) {
                RvSearchSlot { RvSearchField(null, state.query, actions.query, it, hint = hint) }
                Apps(state, actions, look)
            }
        }
    }
}

/** The three policies: radio rows in a card, or, for [RvPerAppLook.Segmented], 策略's segmented control. */
@Composable
private fun Policies(state: RvPerAppState, actions: RvPerAppActions, look: RvPerAppLook) {
    val policies = RvAppPolicy.entries
    if (look == RvPerAppLook.Segmented) {
        val labels = policies.map { stringResource(it.label) }
        return RvSegmented(labels, policies.indexOf(state.edited.policy), { actions.policy(policies[it]) })
    }
    RvSettingsCard {
        policies.forEach { RvSettingsRadioRow(stringResource(it.label), it == state.edited.policy, { actions.policy(it) }) }
    }
}

/**
 * A failed save, one stored whose tunnel would not come back, or apps the phone would not list: errors, with 重試 saving,
 * reconnecting or reading again. A tunnel that found none of the listed apps installed gets 調整清單 instead, which
 * leaves the notice for the list, since connecting again would fail the same way (owner, q-startfail, 2026-11-24).
 */
@Composable
private fun failure(state: RvPerAppState, actions: RvPerAppActions): RvNotice? = when {
    state.save == RvSave.Saving -> null
    state.save == RvSave.Failed -> notice(R.string.rv_perapp_save_failed, state.saveReason, R.string.rv_retry, actions.save)
    state.rebuildFailed && state.rebuildNeedsList -> notice(R.string.rv_perapp_rebuild_failed, state.rebuildReason, R.string.rv_edit_app_list, actions.editList)
    state.rebuildFailed -> notice(R.string.rv_perapp_rebuild_failed, state.rebuildReason, R.string.rv_retry, actions.reconnect)
    state.readFailed -> notice(R.string.perapp_read_failed, state.readReason, R.string.rv_retry, actions.reload)
    else -> null
}

@Composable
private fun notice(@StringRes title: Int, @StringRes reason: Int, @StringRes action: Int, onAction: () -> Unit) =
    RvNotice(RvNoticeKind.Error, stringResource(title), stringResource(reason), stringResource(action), onAction)

/**
 * The apps, or the empty caption when the search matches none or the phone has no app to launch; nothing when the
 * phone would not list its apps, which the notice above says. While they are read, a skeleton of their rows, which a
 * screen reader reads as the apps loading. The skeleton's card runs past the window's foot as the list does
 * ([rvSkeletonRowsToFill]); its rows stop above 儲存's room, as the list's last row clears it ([LocalRvContentFloor]).
 */
@Composable
private fun Apps(state: RvPerAppState, actions: RvPerAppActions, look: RvPerAppLook) {
    val loading = stringResource(R.string.rv_perapp_loading)
    val rows = rvSkeletonRowsToFill()
    RvSkeletonFade(state.installed == null, { RvSkeletonCard(description = loading) { repeat(rows) { RvSkeletonRow(it) } } }) {
        val shown = state.shown
        when {
            state.installed.isNullOrEmpty() && state.readFailed -> Unit
            state.installed.isNullOrEmpty() -> RvEmptyCaption(stringResource(R.string.perapp_no_apps), stringResource(R.string.perapp_no_apps_line))
            shown.isEmpty() -> RvEmptyCaption(stringResource(R.string.perapp_no_match), stringResource(R.string.rv_conn_none_line))
            else -> RvCard { shown.forEach { key(it.packageName) { AppRow(it, it.packageName in state.edited.packages, look) { on -> actions.toggle(it.packageName, on) } } } }
        }
    }
}

/**
 * One app: its own icon (the fallback glyph when the phone does not have it), its name over its package, and a switch
 * that is the checkbox of the list, or [RvCheckRow]'s check outside [RvPerAppLook.Today]; the whole row toggles it, as
 * a switch row of 設定.
 */
@Composable
private fun AppRow(app: RvApp, checked: Boolean, look: RvPerAppLook, onToggle: (Boolean) -> Unit) {
    if (look != RvPerAppLook.Today) return RvCheckRow({ RvAppDisc(app.packageName) }, app.label, app.packageName, checked, onToggle, titleKeepWords = false, subtitleKeepWords = false)
    val toggle = Modifier.rvRowToggle(checked, onToggle)
    RvRow({ RvAppDisc(app.packageName) }, app.label, app.packageName, toggle, titleKeepWords = false, subtitleKeepWords = false, endFirstLine = true) { RvSwitch(checked) }
}

/** Something under way: the sync glyph's disc and the busy bar where a row's end stands, as an export under way. */
@Composable
private fun BusyRow(label: String) {
    RvRow({ RvIconDisc(R.drawable.rv_stats_ic_sync) }, label, null) { RvBusy(Modifier.width(RvSize.busy)) }
}
