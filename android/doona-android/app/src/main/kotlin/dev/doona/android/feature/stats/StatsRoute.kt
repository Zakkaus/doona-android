package dev.doona.android.feature.stats

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.doona.android.engine.Engine

/**
 * 統計, opened from 活動. [onOpenReport] opens 回報問題, where the export runs. A tool that failed heads the page with
 * its banner until it next succeeds. The chips pick the range and the subscription.
 */
@Composable
fun StatsRoute(engine: Engine, onBack: () -> Unit, onOpenReport: () -> Unit) {
    StatsRoute(viewModel<StatsViewModel>(factory = StatsViewModel.factory(engine)), onBack, onOpenReport)
}

@Composable
internal fun StatsRoute(viewModel: StatsViewModel, onBack: () -> Unit, onOpenReport: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val failure by viewModel.failure.collectAsStateWithLifecycle()
    RvStatsScreen(
        state,
        onBack = onBack,
        onSubscription = viewModel::pickSubscription,
        onRange = viewModel::pickRange,
        onTestAll = viewModel::testAll,
        onUpdateAll = viewModel::updateAll,
        onExport = onOpenReport,
        banner = failure?.let { rvStatsFailureBanner(it, viewModel::retry) },
    )
}
