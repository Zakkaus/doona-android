package dev.doona.android.feature.policies

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.doona.android.R
import dev.doona.android.app.nativeApi
import dev.doona.android.designsystem.rv.RvNotice
import dev.doona.android.designsystem.rv.RvNoticeKind
import dev.doona.android.engine.Engine
import dev.doona.android.feature.messageRes

/**
 * The 策略 entry: builds the ViewModel and draws the RV page from its state while the screen is started. [onOpenGroup]
 * pushes a group's page and [onOpenSubscriptions] runs the empty state's 新增訂閱; [routes] carries the page's other
 * links (the main node's picker, all rules, the template, a new rule, the tabs, the search). The bar's two actions are the
 * ViewModel's, and a failed action shows as the page's banner with 重試 until an attempt succeeds (plan 8).
 */
@Composable
internal fun PoliciesRoute(engine: Engine, onOpenGroup: (String) -> Unit, onOpenSubscriptions: () -> Unit, routes: RvPolicyRoutes = RvPolicyRoutes()) {
    val viewModel: PoliciesViewModel = viewModel(factory = PoliciesViewModel.factory(engine, nativeApi()))
    PoliciesRoute(viewModel, onOpenGroup, onOpenSubscriptions, routes)
}

/** 策略 on a given [viewModel]: the page as [PoliciesRoute] draws it, for a caller (a test) that makes its own. */
@Composable
internal fun PoliciesRoute(viewModel: PoliciesViewModel, onOpenGroup: (String) -> Unit, onOpenSubscriptions: () -> Unit, routes: RvPolicyRoutes = RvPolicyRoutes()) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    RvPolicyScreen(
        ui.state,
        viewModel::onChange,
        RvPolicyRoutes(
            group = onOpenGroup,
            mainNode = routes.mainNode,
            rules = routes.rules,
            template = routes.template,
            newRule = routes.newRule,
            tab = routes.tab,
            testAll = viewModel::testAll,
            update = viewModel::updateSubscriptions,
            search = routes.search,
            config = routes.config,
        ),
        noSubscription = onOpenSubscriptions.takeIf { ui.noSubscription },
        loading = ui.loading,
        banner = ui.failure?.let { policyBanner(it, viewModel::retry) },
        profile = ui.profile,
    )
}

/** The banner of a failed [failure]: what failed over the engine's reason, and 重試 ([onRetry]). */
@Composable
private fun policyBanner(failure: PolicyFailure, onRetry: () -> Unit) = RvNotice(
    RvNoticeKind.Error,
    stringResource(failure.action.title),
    stringResource(failure.code.messageRes()),
    stringResource(R.string.rv_retry),
    onRetry,
)

/** What a failed action's banner is titled, as 活動's and the picker's failures are (pv-d M8). */
@get:StringRes
private val PolicyAction.title: Int
    get() = when (this) {
        PolicyAction.Read -> R.string.rv_policy_read_failed
        PolicyAction.Mode -> R.string.rv_failed_switch_mode
        PolicyAction.MainNode -> R.string.rv_failed_switch_node
        PolicyAction.TestAll -> R.string.rv_failed_test_latency
        PolicyAction.Ads -> R.string.rv_policy_failed_ads
        PolicyAction.Quic -> R.string.rv_policy_failed_quic
        PolicyAction.Update -> R.string.rv_policy_failed_update
    }
