package dev.doona.android.feature.stats

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.doona.android.engine.Engine
import dev.doona.android.feature.NOT_WIRED

/**
 * 統計, opened from 活動. [onOpenReport] opens 回報問題, where the export runs. A tool that failed heads the page with
 * its banner until it next succeeds. The subscription chip waits for a read of each subscription's bytes, and the range
 * chip stays on 今日 until its week and month have a sample, so neither is wired.
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
        onSubscriptions = NOT_WIRED,
        onRange = NOT_WIRED,
        onTestAll = viewModel::testAll,
        onUpdateAll = viewModel::updateAll,
        onExport = onOpenReport,
        banner = failure?.let { rvStatsFailureBanner(it, viewModel::retry) },
    )
}
