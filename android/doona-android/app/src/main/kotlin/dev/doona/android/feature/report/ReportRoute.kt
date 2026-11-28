package dev.doona.android.feature.report

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.doona.android.app.nativeApi
import dev.doona.android.engine.Engine
import dev.doona.android.report.ReportExporter

/** The 回報問題 entry: builds the ViewModel over the [engine], honk, the app's [reports] and its [facts], and shows the page. */
@Composable
internal fun ReportRoute(engine: Engine, reports: ReportExporter, facts: ReportFacts, onBack: () -> Unit) {
    val api = nativeApi()
    val viewModel: ReportViewModel = viewModel(factory = ReportViewModel.factory(engine, api, reports, facts))
    ReportPage(viewModel, onBack)
}

/**
 * The page on [viewModel]'s state. 分享檔案 opens the share sheet on the written report and tells the ViewModel when
 * it returns, or that it could not open (the report then stays ready); 在 GitHub 回報 opens the report's new-issue link in the browser, and when no
 * app opens it the page's banner says so.
 */
@Composable
internal fun ReportPage(viewModel: ReportViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val actions = remember(viewModel, context) {
        RvReportActions(
            github = {
                viewModel.uiState.value.share?.issueLink?.let { link ->
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
                    } catch (_: ActivityNotFoundException) {
                        // No app opens the link: the page says so, and its banner offers the share sheet instead.
                        viewModel.linkFailed()
                    }
                }
            },
            share = {
                viewModel.share { share ->
                    try {
                        context.startActivity(share.intent)
                        viewModel.shareOpened(share)
                    } catch (_: ActivityNotFoundException) {
                        viewModel.shareFailed(share)
                    }
                }
            },
            retry = viewModel::retry,
        )
    }
    val preview = state.share?.let { state.preview.copy(events = it.eventCount, crashes = it.crashCount) } ?: state.preview
    RvReportScreen(state.build, preview, state.redaction, viewModel::setRedaction, onBack, actions, linkFailed = state.linkFailed)
}
