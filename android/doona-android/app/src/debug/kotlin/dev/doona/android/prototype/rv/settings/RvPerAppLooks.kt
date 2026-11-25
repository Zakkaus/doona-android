package dev.doona.android.prototype.rv.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.doona.android.designsystem.rv.RvAppDisc
import dev.doona.android.designsystem.rv.RvCheckRow
import dev.doona.android.designsystem.rv.RvSegmented
import dev.doona.android.feature.model.RvAppPolicy
import dev.doona.android.feature.perapp.RvApp
import dev.doona.android.feature.perapp.RvPerAppLook
import dev.doona.android.feature.perapp.RvPerAppPolicyCard

/** `perapp-v1`, a sample the owner did not pick: the policies' card, and each app checked with [RvCheckRow]. */
internal val RvPerAppLook.Companion.Checks: RvPerAppLook get() = checks

/** `perapp-v2`, a sample the owner did not pick: the policies in 策略's segmented control under the title, each app checked with [RvCheckRow]. */
internal val RvPerAppLook.Companion.Segmented: RvPerAppLook get() = segmented

private val checks = RvPerAppLook({ state, actions -> RvPerAppPolicyCard(state, actions) }, { app, checked, onToggle -> CheckRow(app, checked, onToggle) })

private val segmented = RvPerAppLook(
    { state, actions ->
        val policies = RvAppPolicy.entries
        RvSegmented(policies.map { stringResource(it.label) }, policies.indexOf(state.edited.policy), { actions.policy(policies[it]) })
    },
    { app, checked, onToggle -> CheckRow(app, checked, onToggle) },
)

@Composable
private fun CheckRow(app: RvApp, checked: Boolean, onToggle: (Boolean) -> Unit) =
    RvCheckRow({ RvAppDisc(app.packageName) }, app.label, app.packageName, checked, onToggle, titleKeepWords = false, subtitleKeepWords = false)
