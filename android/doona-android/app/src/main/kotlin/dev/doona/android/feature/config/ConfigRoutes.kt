package dev.doona.android.feature.config

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.doona.android.R
import dev.doona.android.designsystem.rv.LocalFrameInsets
import dev.doona.android.designsystem.rv.RvBannerSlot
import dev.doona.android.designsystem.rv.RvLargeTitle
import dev.doona.android.designsystem.rv.RvNotice
import dev.doona.android.designsystem.rv.RvNoticeKind
import dev.doona.android.designsystem.rv.RvScrollPage
import dev.doona.android.engine.Engine
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.ExportKind
import dev.doona.android.feature.messageRes

/** Where the configuration pages lead; each is a push of the app's back stack. */
internal class ConfigRoutes(
    val file: (String) -> Unit = {},
    val skipped: () -> Unit = {},
    val effective: () -> Unit = {},
    val history: () -> Unit = {},
    val import: () -> Unit = {},
    val revision: (Int) -> Unit = {},
    /** The export token, for the picker to stream (slice 13). */
    val export: (String) -> Unit = {},
)

/** A read that failed, with 重試. */
@Composable
private fun readFailed(failure: ErrorCode?, retry: () -> Unit): RvNotice? = failure?.let {
    RvNotice(RvNoticeKind.Error, stringResource(R.string.rv_config_read_failed), stringResource(it.messageRes()), stringResource(R.string.rv_retry), retry)
}

/** A configuration page before its first read: its title, and the failure of that read. */
@Composable
private fun ConfigWaitPage(title: Int, onBack: () -> Unit, banner: RvNotice?) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val text = stringResource(title)
    RvScrollPage(text, insets, onBack = onBack) { titleBlock ->
        RvLargeTitle(text, titleBlock, insets)
        RvBannerSlot(banner)
    }
}

@Composable
internal fun ConfigOverviewRoute(engine: Engine, onBack: () -> Unit, routes: ConfigRoutes) =
    ConfigOverviewRoute(viewModel<ConfigOverviewViewModel>(factory = viewModelFactory { initializer { ConfigOverviewViewModel(engine) } }), onBack, routes)

/** 設定檔 on a given [viewModel], for a caller (a test) that makes its own. */
@Composable
internal fun ConfigOverviewRoute(viewModel: ConfigOverviewViewModel, onBack: () -> Unit, routes: ConfigRoutes) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val config = ui.value ?: return ConfigWaitPage(R.string.rv_config_title, onBack, readFailed(ui.failure, viewModel::retry))
    RvConfigOverviewPage(config, onBack, routes.file, routes.skipped, routes.effective, routes.history, routes.import)
}

@Composable
internal fun ConfigSkippedRoute(engine: Engine, onBack: () -> Unit, onOpen: (String) -> Unit) =
    ConfigSkippedRoute(viewModel<ConfigOverviewViewModel>(factory = viewModelFactory { initializer { ConfigOverviewViewModel(engine) } }), onBack, onOpen)

/** 已略過: a row opens its file ([onOpen] gets the path). */
@Composable
internal fun ConfigSkippedRoute(viewModel: ConfigOverviewViewModel, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val config = ui.value ?: return ConfigWaitPage(R.string.rv_rules_skipped, onBack, readFailed(ui.failure, viewModel::retry))
    RvConfigSkippedPage(config, onBack) { onOpen(it.source) }
}

@Composable
internal fun ConfigSourceRoute(engine: Engine, path: String, onBack: () -> Unit, onApplied: () -> Unit) =
    ConfigSourceRoute(viewModel<ConfigSourceViewModel>(key = path, factory = viewModelFactory { initializer { ConfigSourceViewModel(engine, path) } }), onBack, onApplied)

/**
 * 原始碼 and, once 檢視變更 passed, its diff over it; Back from the diff returns to the editor with the draft kept, Back
 * from the editor drops the draft. [onApplied] runs once 套用 made a new head.
 */
@Composable
internal fun ConfigSourceRoute(viewModel: ConfigSourceViewModel, onBack: () -> Unit, onApplied: () -> Unit) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(ui.applied) { if (ui.applied) onApplied() }
    val draft = ui.draft ?: return ConfigWaitPage(R.string.rv_config_source, onBack, readFailed(ui.failure, viewModel::reload))
    val diff = ui.diff
    if (diff == null) {
        RvConfigSourcePage(draft, ui.value, viewModel::onValueChange, onBack, onReview = viewModel::review)
    } else {
        val failure = ui.failure?.let { RvNotice(RvNoticeKind.Error, stringResource(R.string.rv_config_apply_failed), stringResource(it.messageRes()), stringResource(R.string.rv_config_reload), viewModel::reload) }
        RvConfigDiffPage(diff, viewModel::closeDiff, viewModel::apply, viewModel::reload, failure = failure)
    }
}

@Composable
internal fun ConfigHistoryRoute(engine: Engine, onBack: () -> Unit, routes: ConfigRoutes) =
    ConfigHistoryRoute(viewModel<ConfigHistoryViewModel>(factory = viewModelFactory { initializer { ConfigHistoryViewModel(engine) } }), onBack, routes)

/**
 * 版本紀錄; 匯出設定檔 asks first, since the files can carry credentials, and which kind, then stages the head; a refused
 * portable copy's banner reopens the sheet on the archive.
 */
@Composable
internal fun ConfigHistoryRoute(viewModel: ConfigHistoryViewModel, onBack: () -> Unit, routes: ConfigRoutes, confirmExport: Boolean = false) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val rename by viewModel.rename.collectAsStateWithLifecycle()
    val exported by viewModel.exported.collectAsStateWithLifecycle()
    val notice by viewModel.exportNotice.collectAsStateWithLifecycle()
    var asking by rememberSaveable { mutableStateOf(confirmExport) }
    var kind by rememberSaveable { mutableStateOf(ExportKind.Archive) }
    LaunchedEffect(exported) {
        exported?.let {
            routes.export(it)
            viewModel.exportHandled()
        }
    }
    val history = ui.value ?: return ConfigWaitPage(R.string.rv_config_history, onBack, readFailed(ui.failure, viewModel::retry))
    val banner = rvExportBanner(
        notice,
        onArchive = {
            viewModel.exportNoticeSeen()
            kind = ExportKind.Archive
            asking = true
        },
        onSeen = viewModel::exportNoticeSeen,
    )
    RvConfigHistoryPage(history.revisions, history.headRev, history.nowEpochMs, onBack, routes.revision, { asking = true }, rename, viewModel::onRename, viewModel::saveName, banner)
    RvConfigExportSheet(
        visible = asking,
        kind = kind,
        onKind = { kind = it },
        onExport = {
            asking = false
            viewModel.export(it)
        },
        onDismiss = { asking = false },
    )
}

@Composable
internal fun ConfigRevisionRoute(engine: Engine, rev: Int, onBack: () -> Unit, onRestored: () -> Unit) =
    ConfigRevisionRoute(viewModel<ConfigRevisionViewModel>(key = "rev-$rev", factory = viewModelFactory { initializer { ConfigRevisionViewModel(engine, rev) } }), onBack, onRestored)

/** A revision's page; [onRestored] runs once 還原 made a new head. */
@Composable
internal fun ConfigRevisionRoute(viewModel: ConfigRevisionViewModel, onBack: () -> Unit, onRestored: () -> Unit) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val rename by viewModel.rename.collectAsStateWithLifecycle()
    val restored by viewModel.restored.collectAsStateWithLifecycle()
    LaunchedEffect(restored) { if (restored) onRestored() }
    val view = ui.value ?: return ConfigWaitPage(R.string.rv_config_history, onBack, readFailed(ui.failure, viewModel::retry))
    RvConfigRevisionPage(view.revision, view.files, view.nowEpochMs, onBack, viewModel::restore, rename = rename, onRename = viewModel::onRename, onSaveName = viewModel::saveName)
}

@Composable
internal fun ConfigImportRoute(engine: Engine, token: String, name: String, onBack: () -> Unit, onPickAgain: () -> Unit) =
    ConfigImportRoute(viewModel<ConfigImportViewModel>(key = token, factory = viewModelFactory { initializer { ConfigImportViewModel(engine, token, name) } }), onBack, onPickAgain)

/** 匯入設定檔 for a staged pick. */
@Composable
internal fun ConfigImportRoute(viewModel: ConfigImportViewModel, onBack: () -> Unit, onPickAgain: () -> Unit) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val state = ui.value ?: return ConfigWaitPage(R.string.rv_config_import, onBack, readFailed(ui.failure, viewModel::retry))
    RvConfigImportPage(state, onBack, onPickAgain)
}
