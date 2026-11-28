package dev.doona.android.feature.policies

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.doona.android.R
import dev.doona.android.app.nativeApi
import dev.doona.android.designsystem.rv.RvNotice
import dev.doona.android.designsystem.rv.RvNoticeKind
import dev.doona.android.feature.messageRes
import dev.doona.android.feature.settings.changeBanner

/**
 * 全部規則 (查看全部 on 策略): builds the ViewModel on the app's [nativeApi] and draws the page from its state while the
 * screen is started, always editable. A rule opens its page ([onOpen] with honk's rule id), 新增's 規則 a new rule's
 * ([onNew]) and its 服務規則 新增服務規則 ([onService]); 刪除 and 停用 write honk's file here; the reorder mode is the page's own. Coming back to the page reads
 * again, since a rule's page may have changed the rules. Files honk did not load show their banner with 範本
 * ([onTemplate]); a failed read, with 重試.
 */
@Composable
internal fun RulesRoute(
    onBack: () -> Unit,
    onTemplate: () -> Unit,
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
    onService: () -> Unit,
    routes: RvRulesRoutes = RvRulesRoutes(),
) {
    val viewModel: RulesViewModel = viewModel(factory = RulesViewModel.factory(nativeApi()))
    RulesRoute(viewModel, onBack, onTemplate, onOpen, onNew, routes, onService)
}

/** 全部規則 on a given [viewModel], for a caller (a test) that makes its own. */
@Composable
internal fun RulesRoute(
    viewModel: RulesViewModel,
    onBack: () -> Unit,
    onTemplate: () -> Unit,
    onOpen: (String) -> Unit = {},
    onNew: () -> Unit = {},
    routes: RvRulesRoutes = RvRulesRoutes(),
    onService: () -> Unit = {},
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    var editing by rememberSaveable { mutableStateOf(false) }
    RvReadOnReturn(viewModel::load)
    RvRulesPage(
        ui.rules,
        pending = emptyList(),
        editing = editing,
        onBack = onBack,
        routes = RvRulesRoutes(
            open = { i -> ui.ids.getOrNull(i)?.takeUnless { i == ui.rules.lastIndex }?.let { id -> { onOpen(id) } } },
            toggle = viewModel::toggle,
            delete = viewModel::remove,
            edit = { editing = it },
            newRule = onNew,
            service = onService,
            apply = routes.apply,
            discard = routes.discard,
            effective = routes.effective,
        ),
        banner = ui.failure?.let { rulesBanner(it, onTemplate, viewModel::retry) }
            ?: ui.writeFailure?.let { changeBanner(stringResource(R.string.rv_config_apply_failed), it, viewModel::retryWrite) },
        loading = ui.loading,
        removable = true,
    )
}

/**
 * Calls [read] each time the screen resumes after the first: a page under another one (a rule's page) reads again when
 * it shows, until honk's events tell the app what changed.
 */
@Composable
internal fun RvReadOnReturn(read: () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    LifecycleResumeEffect(read) {
        if (shown) read() else shown = true
        onPauseOrDispose {}
    }
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
