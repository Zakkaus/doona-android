package dev.doona.android.feature.policies

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.doona.android.R
import dev.doona.android.app.nativeApi
import dev.doona.android.designsystem.rv.RvNotice
import dev.doona.android.designsystem.rv.RvNoticeKind
import dev.doona.android.feature.messageRes

/** 路由測試 on the app's [nativeApi], with [carries] telling whether the tun carries a host; [onOpenSource] opens a file the answer names. */
@Composable
internal fun RouteTraceRoute(carries: (String) -> Boolean?, onBack: () -> Unit, onOpenSource: (String) -> Unit) {
    RouteTraceRoute(viewModel<RouteTraceViewModel>(factory = RouteTraceViewModel.factory(nativeApi(), carries)), onBack, onOpenSource)
}

/** 路由測試 on a given [viewModel], for a caller (a test) that makes its own. A failed trace shows its banner with 重試. */
@Composable
internal fun RouteTraceRoute(viewModel: RouteTraceViewModel, onBack: () -> Unit, onOpenSource: (String) -> Unit) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val banner = ui.failure?.let {
        RvNotice(RvNoticeKind.Error, stringResource(R.string.rv_trace_failed), stringResource(it.messageRes()), stringResource(R.string.rv_retry), viewModel::trace)
    }
    RvRouteTracePage(ui.query, viewModel::query, onBack, ui.result, viewModel::trace, { file, _ -> onOpenSource(file) }, banner)
}
