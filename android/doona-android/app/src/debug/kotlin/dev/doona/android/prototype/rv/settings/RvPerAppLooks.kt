package dev.doona.android.prototype.rv.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.res.stringResource
import dev.doona.android.designsystem.rv.RvAppDisc
import dev.doona.android.designsystem.rv.RvCheckRow
import dev.doona.android.designsystem.rv.RvSegmented
import dev.doona.android.feature.model.RvAppPolicy
import dev.doona.android.feature.perapp.RvApp
import dev.doona.android.feature.perapp.RvPerAppActions
import dev.doona.android.feature.perapp.RvPerAppPolicyCard
import dev.doona.android.feature.perapp.RvPerAppScreen
import dev.doona.android.feature.perapp.RvPerAppState

/**
 * A look of 依 App the owner did not pick: how it draws its [policies] and each [app] (checked or not, and what toggles
 * it). The app draws the approved one, which is no look of these.
 */
@Immutable
internal class RvPerAppLook(
    val policies: @Composable (RvPerAppState, RvPerAppActions) -> Unit,
    val app: @Composable (RvApp, Boolean, (Boolean) -> Unit) -> Unit,
) {
    companion object {
        /** `perapp-v1`: the policies' card, and each app checked with [RvCheckRow]. */
        val Checks = RvPerAppLook({ state, actions -> RvPerAppPolicyCard(state, actions) }, { app, checked, onToggle -> CheckRow(app, checked, onToggle) })

        /** `perapp-v2`: the policies in 策略's segmented control under the title, each app checked with [RvCheckRow]. */
        val Segmented = RvPerAppLook(
            { state, actions ->
                val policies = RvAppPolicy.entries
                RvSegmented(policies.map { stringResource(it.label) }, policies.indexOf(state.edited.policy), { actions.policy(policies[it]) })
            },
            { app, checked, onToggle -> CheckRow(app, checked, onToggle) },
        )
    }
}

/** 依 App drawn in [look]. */
@Composable
internal fun RvPerAppLookScreen(state: RvPerAppState, actions: RvPerAppActions, onBack: () -> Unit, look: RvPerAppLook) =
    RvPerAppScreen(state, actions, onBack, look.policies, look.app)

/** The page's actions on a sample: each one the caller leaves out does nothing. */
internal fun rvPerAppActions(
    policy: (RvAppPolicy) -> Unit = {},
    toggle: (String, Boolean) -> Unit = { _, _ -> },
    query: (String) -> Unit = {},
    save: () -> Unit = {},
    reconnect: () -> Unit = {},
    addSubscription: () -> Unit = {},
    editList: () -> Unit = {},
    reload: () -> Unit = {},
    checkUpdate: () -> Unit = {},
    vpnSettings: () -> Unit = {},
) = RvPerAppActions(policy, toggle, query, save, reconnect, addSubscription, editList, reload, checkUpdate, vpnSettings)

@Composable
private fun CheckRow(app: RvApp, checked: Boolean, onToggle: (Boolean) -> Unit) =
    RvCheckRow({ RvAppDisc(app.packageName) }, app.label, app.packageName, checked, onToggle, titleKeepWords = false, subtitleKeepWords = false)
