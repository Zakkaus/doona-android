package dev.doona.android.feature.stats

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.doona.android.app.nativeApi

/**
 * 統計, opened from 活動. [onOpenReport] opens 回報問題, where the export runs. A tool that failed heads the page with
 * its banner until it next succeeds. The chips pick the range and the subscription. honk's profile is read each time the
 * page starts.
 */
@Composable
fun StatsRoute(onBack: () -> Unit, onOpenReport: () -> Unit) {
    val viewModel = viewModel<StatsViewModel>(factory = StatsViewModel.factory(nativeApi()))
    LifecycleStartEffect(viewModel) {
        viewModel.load()
        onStopOrDispose {}
    }
    StatsRoute(viewModel, onBack, onOpenReport)
}

@Composable
internal fun StatsRoute(viewModel: StatsViewModel, onBack: () -> Unit, onOpenReport: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val failure by viewModel.failure.collectAsStateWithLifecycle()
    val running by viewModel.runningTools.collectAsStateWithLifecycle()
    RvStatsScreen(
        state,
        onBack = onBack,
        onSubscription = viewModel::pickSubscription,
        onRange = viewModel::pickRange,
        onTestAll = if (StatsTool.TestAll in running) null else viewModel::testAll,
        onUpdateAll = if (StatsTool.UpdateAll in running) null else viewModel::updateAll,
        onExport = onOpenReport,
        banner = failure?.takeUnless { it.tool in running }?.let { rvStatsFailureBanner(it, viewModel::retry) },
    )
}
