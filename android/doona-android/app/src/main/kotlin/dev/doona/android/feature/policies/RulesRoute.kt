package dev.doona.android.feature.policies

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.doona.android.R
import dev.doona.android.app.nativeApi
import dev.doona.android.designsystem.rv.RvNotice
import dev.doona.android.designsystem.rv.RvNoticeKind
import dev.doona.android.feature.messageRes

/**
 * 全部規則 (查看全部 on 策略): builds the ViewModel on the app's [nativeApi] and draws the page from its state while the
 * screen is started. honk's native API has no call yet that edits one rule, so [routes] carries the taps that open,
 * edit, turn off, delete, add, apply and drop rules as the caller wires them; the reorder mode is the page's own. Files
 * honk did not load show their banner with 範本 ([onTemplate]); a failed read, with 重試.
 */
@Composable
internal fun RulesRoute(onBack: () -> Unit, onTemplate: () -> Unit, routes: RvRulesRoutes = RvRulesRoutes()) {
    val viewModel: RulesViewModel = viewModel(factory = RulesViewModel.factory(nativeApi()))
    RulesRoute(viewModel, onBack, onTemplate, routes)
}

/** 全部規則 on a given [viewModel], for a caller (a test) that makes its own. */
@Composable
internal fun RulesRoute(viewModel: RulesViewModel, onBack: () -> Unit, onTemplate: () -> Unit, routes: RvRulesRoutes = RvRulesRoutes()) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    var editing by rememberSaveable { mutableStateOf(false) }
    RvRulesPage(
        ui.rules,
        pending = emptyList(),
        editing = editing,
        onBack = onBack,
        routes = RvRulesRoutes(
            open = routes.open,
            toggle = routes.toggle,
            delete = routes.delete,
            edit = { editing = it },
            newRule = routes.newRule,
            apply = routes.apply,
            discard = routes.discard,
            effective = routes.effective,
        ),
        banner = ui.failure?.let { rulesBanner(it, onTemplate, viewModel::retry) },
        loading = ui.loading,
    )
}

/** The banner of [failure], as the approved error samples have it: files honk did not load open 範本; a read retries. */
@Composable
private fun rulesBanner(failure: RulesFailure, onTemplate: () -> Unit, onRetry: () -> Unit): RvNotice = when (failure) {
    RulesFailure.Parse -> RvNotice(
        RvNoticeKind.Error,
        stringResource(R.string.rv_rules_parse_failed),
        stringResource(R.string.rv_rules_parse_failed_reason),
        stringResource(R.string.rv_policy_template),
        onTemplate,
    )

    is RulesFailure.Read -> RvNotice(
        RvNoticeKind.Error,
        stringResource(R.string.rv_rules_read_failed),
        stringResource(failure.code.messageRes()),
        stringResource(R.string.rv_retry),
        onRetry,
    )
}
