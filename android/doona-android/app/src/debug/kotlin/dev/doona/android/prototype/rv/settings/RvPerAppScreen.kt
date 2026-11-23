package dev.doona.android.prototype.rv.settings

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import dev.doona.android.R
import dev.doona.android.designsystem.theme.LocalFrameInsets
import dev.doona.android.prototype.rv.RvAppDisc
import dev.doona.android.prototype.rv.RvBanner
import dev.doona.android.prototype.rv.RvBusy
import dev.doona.android.prototype.rv.RvCard
import dev.doona.android.prototype.rv.RvCheckRow
import dev.doona.android.prototype.rv.RvEmptyCaption
import dev.doona.android.prototype.rv.RvFieldMessage
import dev.doona.android.prototype.rv.RvIconDisc
import dev.doona.android.prototype.rv.RvLargeTitle
import dev.doona.android.prototype.rv.RvPart
import dev.doona.android.prototype.rv.RvPrimaryButton
import dev.doona.android.prototype.rv.RvRow
import dev.doona.android.prototype.rv.RvScrollPage
import dev.doona.android.prototype.rv.RvSearchField
import dev.doona.android.prototype.rv.RvSegmented
import dev.doona.android.prototype.rv.RvSize
import dev.doona.android.prototype.rv.RvSpace
import dev.doona.android.prototype.rv.RvSwitch
import dev.doona.android.prototype.rv.RvTheme
import dev.doona.android.prototype.rv.rvRowIndication
import dev.doona.android.prototype.rv.rvSystemScheme

/** What the 依 App page does: pick a policy, check an app, search, save, and rebuild the tunnel after a failed one. */
internal class RvPerAppActions(
    val policy: (RvAppPolicy) -> Unit = {},
    val toggle: (String, Boolean) -> Unit = { _, _ -> },
    val query: (String) -> Unit = {},
    val save: () -> Unit = {},
    val reconnect: () -> Unit = {},
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
 * and the search field ([RvSearchField], the node picker's) stands over that card, a control row of the page; a search
 * that matches nothing says so in the library's empty caption. 只有勾選的 with nothing checked says what it needs under the policies, as a field says
 * its error ([RvFieldMessage]), and cannot be saved. 儲存 floats at the foot, enabled only when the edits can be saved, since a save rebuilds
 * the tunnel. A save under way, a failed one and a tunnel that would not come back after one show at the top: a busy
 * row, or the library's notice card with 重試.
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
        Column(Modifier.fillMaxWidth().padding(horizontal = RvSpace.gutter)) {
            Status(state, actions)
            // The policies and the error under them are one block of the column, as a field and its message are.
            Column(Modifier.testTag(RvPart.BLOCK)) {
                Policies(state, actions, look)
                if (state.needsApp) RvFieldMessage(stringResource(R.string.rv_perapp_needs_app))
            }
            if (listed) {
                // The segmented control and the search are two control rows stacked in one, as a node list's search and order.
                Spacer(Modifier.height(if (look == RvPerAppLook.Segmented) RvSpace.controlStack else RvSpace.controlGap))
                RvSearchField(null, state.query, actions.query, Modifier.fillMaxWidth().testTag(RvPart.CONTROL), hint = hint)
                Spacer(Modifier.height(RvSpace.controlGap))
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

/** The save's state over the policies, followed by a card's gap: under way, failed, or stored without a tunnel. */
@Composable
private fun Status(state: RvPerAppState, actions: RvPerAppActions) {
    when {
        state.save == RvSave.Saving -> RvCard { BusyRow(stringResource(R.string.rv_perapp_saving)) }

        state.save == RvSave.Failed ->
            RvBanner(stringResource(R.string.rv_perapp_save_failed), stringResource(R.string.rv_report_failed_reason), stringResource(R.string.rv_retry), actions.save)

        state.rebuildFailed ->
            RvBanner(stringResource(R.string.rv_perapp_rebuild_failed), stringResource(R.string.vpn_failure_start_failed), stringResource(R.string.rv_retry), actions.reconnect)

        else -> return
    }
    Spacer(Modifier.height(RvSpace.cardGap))
}

/** The apps, a row saying they are being read, or the empty caption when the search matches none. */
@Composable
private fun Apps(state: RvPerAppState, actions: RvPerAppActions, look: RvPerAppLook) {
    val shown = state.shown
    when {
        state.installed == null -> RvCard { BusyRow(stringResource(R.string.rv_perapp_loading)) }
        shown.isEmpty() -> RvEmptyCaption(stringResource(R.string.perapp_no_match), stringResource(R.string.rv_conn_none_line))
        else -> RvCard { shown.forEach { AppRow(it, it.packageName in state.edited.packages, look) { on -> actions.toggle(it.packageName, on) } } }
    }
}

/**
 * One app: its own icon (the fallback glyph when the phone does not have it), its name over its package, and a switch
 * that is the checkbox of the list, or [RvCheckRow]'s check outside [RvPerAppLook.Today]; the whole row toggles it, as
 * a switch row of 設定.
 */
@Composable
private fun AppRow(app: RvApp, checked: Boolean, look: RvPerAppLook, onToggle: (Boolean) -> Unit) {
    if (look != RvPerAppLook.Today) return RvCheckRow({ RvAppDisc(app.packageName) }, app.label, app.packageName, checked, onToggle)
    val toggle = Modifier.toggleable(value = checked, interactionSource = null, indication = rvRowIndication(), role = Role.Switch, onValueChange = onToggle)
    RvRow({ RvAppDisc(app.packageName) }, app.label, app.packageName, toggle) { RvSwitch(checked) }
}

/** Something under way: the sync glyph's disc and the busy bar where a row's end stands, as an export under way. */
@Composable
private fun BusyRow(label: String) {
    RvRow({ RvIconDisc(R.drawable.rv_stats_ic_sync) }, label, null) { RvBusy(Modifier.width(RvSize.busy)) }
}

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RvPerAppScreenPreview() {
    val dark = isSystemInDarkTheme()
    var state by remember { mutableStateOf(rvPerAppSample("")) }
    val actions = RvPerAppActions(policy = { state = state.withPolicy(it) }, toggle = { pkg, on -> state = state.withApp(pkg, on) }, query = { state = state.copy(query = it) })
    RvTheme(rvSystemScheme(dark), dark) { RvPerAppScreen(state, actions, onBack = {}) }
}
