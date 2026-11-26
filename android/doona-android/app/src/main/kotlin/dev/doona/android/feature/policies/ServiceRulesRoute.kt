package dev.doona.android.feature.policies

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.doona.android.app.nativeApi

/** 新增服務規則 on the app's [nativeApi]. */
@Composable
internal fun ServiceRulesRoute(onBack: () -> Unit) {
    ServiceRulesRoute(viewModel(factory = ServiceRulesViewModel.factory(nativeApi())), onBack)
}

/** 新增服務規則 on a given [viewModel], for a caller (a test) that makes its own. */
@Composable
internal fun ServiceRulesRoute(viewModel: ServiceRulesViewModel, onBack: () -> Unit) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    RvServiceRulesPage(ui.main, ui.rules, ui.selected, onBack, viewModel::toggle)
}
