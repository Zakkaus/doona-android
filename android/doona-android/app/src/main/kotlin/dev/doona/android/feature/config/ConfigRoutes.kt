package dev.doona.android.feature.config

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.doona.android.R
import dev.doona.android.designsystem.rv.LocalFrameInsets
import dev.doona.android.designsystem.rv.RV_NO_VALUE
import dev.doona.android.designsystem.rv.RvBannerSlot
import dev.doona.android.designsystem.rv.RvCard
import dev.doona.android.designsystem.rv.RvLargeTitle
import dev.doona.android.designsystem.rv.RvListEdit
import dev.doona.android.designsystem.rv.RvNotice
import dev.doona.android.designsystem.rv.RvNoticeKind
import dev.doona.android.designsystem.rv.RvScrollPage
import dev.doona.android.designsystem.rv.RvSettingsNavRow
import dev.doona.android.designsystem.rv.RvStatRow
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.ExportKind
import dev.doona.android.feature.messageRes
import dev.doona.android.feature.model.RvRuleSource
import dev.doona.android.feature.model.rvTemplate
import dev.doona.android.feature.policies.RvTemplateRow
import dev.doona.android.feature.policies.TemplateViewModel
import dev.doona.android.feature.settings.changeBanner
import dev.doona.android.honk.NativeApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal val PRIMARY_TEMPLATE_IDS = listOf("cn-direct", "gfw", "global")
internal val MORE_TEMPLATE_IDS = listOf("single", "services", "regions", "homebound")

/** Where the configuration pages lead; each is a push of the app's back stack. */
internal class ConfigRoutes(
    val file: (String) -> Unit = {},
    val skipped: () -> Unit = {},
    val effective: () -> Unit = {},
    val history: () -> Unit = {},
    val revision: (Int) -> Unit = {},
    /** honk's export of the accepted files, for the picker to write (slice 13). */
    val export: (String) -> Unit = {},
    val template: () -> Unit = {},
)

@Composable
internal fun ConfigTemplateRoute(api: NativeApi, onBack: () -> Unit) {
    val vm: TemplateViewModel = viewModel(factory = TemplateViewModel.factory(api))
    val state by vm.uiState.collectAsStateWithLifecycle()
    val current = (state.source as? RvRuleSource.Template)?.template?.id
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var ads by rememberSaveable { mutableStateOf(false) }
    var quic by rememberSaveable { mutableStateOf(true) }
    val rows: @Composable (List<String>) -> Unit = { ids ->
        RvCard {
            ids.forEach { id ->
                val available = state.templates?.find { it.id == id }
                RvTemplateRow(available ?: rvTemplate(id), id == (selected ?: current), id == current, kept = true, onSelect = available?.let { { selected = id } })
            }
        }
    }
    // honk-gap:config-template-create — no validated group preview or creation contract.
    RvConfigTemplateForm(
        onBack,
        R.string.rv_config_create,
        onNext = null,
        primary = {
            RvBannerSlot(readFailed(state.failure, vm::retry))
            rows(PRIMARY_TEMPLATE_IDS)
        },
        more = { rows(MORE_TEMPLATE_IDS) },
        ads = ads,
        quic = quic,
        onAds = { ads = it },
        onQuic = { quic = it },
    ) {
        RvCard { RvStatRow(stringResource(R.string.rv_policy_groups), RV_NO_VALUE) }
    }
}

/** A read that failed, with 重試. */
@Composable
private fun readFailed(failure: ErrorCode?, retry: () -> Unit): RvNotice? = failure?.let {
    RvNotice(RvNoticeKind.Error, stringResource(R.string.rv_config_read_failed), stringResource(it.messageRes()), stringResource(R.string.rv_retry), retry)
}

/** A configuration page before its first read: its title, and the failure of that read. */
@Composable
private fun ConfigWaitPage(title: Int, onBack: () -> Unit, banner: RvNotice?, content: @Composable () -> Unit = {}) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val text = stringResource(title)
    RvScrollPage(text, insets, onBack = onBack) { titleBlock ->
        RvLargeTitle(text, titleBlock, insets)
        RvBannerSlot(banner)
        content()
    }
}

@Composable
internal fun ConfigOverviewRoute(api: NativeApi, onBack: () -> Unit, routes: ConfigRoutes) =
    ConfigOverviewRoute(viewModel<ConfigOverviewViewModel>(factory = ConfigOverviewViewModel.factory(api)), onBack, routes)

/**
 * 設定檔 on a given [viewModel], for a caller (a test) that makes its own. 匯入設定檔 opens the system picker and hands
 * the picked file's text to [ConfigOverviewViewModel.import]; how it ended is the page's banner.
 */
@Composable
internal fun ConfigOverviewRoute(viewModel: ConfigOverviewViewModel, onBack: () -> Unit, routes: ConfigRoutes) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val imported by viewModel.imported.collectAsStateWithLifecycle()
    val writeFailure by viewModel.writeFailure.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) { runCatching { context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() } }.getOrNull() }
            if (text != null) viewModel.import(text)
        }
    }
    val config = ui.value ?: return ConfigWaitPage(R.string.rv_config_title, onBack, readFailed(ui.failure, viewModel::retry)) {
        RvSettingsNavRow(stringResource(R.string.rv_policy_template), R.drawable.rv_sym_rule, routes.template)
    }
    RvConfigOverviewPage(
        config,
        onBack,
        routes.file,
        routes.skipped,
        routes.effective,
        routes.history,
        onImport = { picker.launch(arrayOf("*/*")) },
        onTemplate = routes.template,
        // The files list is always editable (owner, q-edit-wire 2026-11-26): 新增檔案 opens the entry file, where an
        // include is written.
        edit = RvListEdit(onAdd = { config.files.firstOrNull { it.parent == null }?.path?.let(routes.file) }, onRemove = viewModel::removeFile),
        banner = imported?.let { importBanner(it, viewModel::importSeen) { picker.launch(arrayOf("*/*")) } }
            ?: writeFailure?.let { changeBanner(stringResource(R.string.rv_config_apply_failed), it, viewModel::retry) },
    )
}

/** How 匯入 ended: done (read, it closes), or rejected or failed with 選擇其他檔案. */
@Composable
private fun importBanner(imported: RvConfigImport, onSeen: () -> Unit, onPick: () -> Unit): RvNotice = when (imported) {
    RvConfigImport.Done -> RvNotice(
        RvNoticeKind.Info,
        stringResource(R.string.rv_config_import_done),
        stringResource(R.string.rv_config_import_done_reason),
        stringResource(R.string.rv_done),
        onSeen,
    )

    is RvConfigImport.Rejected -> RvNotice(
        RvNoticeKind.Error,
        stringResource(R.string.rv_config_import_refused),
        imported.message ?: stringResource(R.string.rv_config_import_rejected_reason),
        stringResource(R.string.rv_config_pick_again),
        onPick,
        reasonKeepWords = false,
    )

    is RvConfigImport.Failed -> RvNotice(
        RvNoticeKind.Error,
        stringResource(R.string.rv_config_import_refused),
        stringResource(imported.code.messageRes()),
        stringResource(R.string.rv_config_pick_again),
        onPick,
    )
}

@Composable
internal fun ConfigSkippedRoute(api: NativeApi, onBack: () -> Unit, onOpen: (String) -> Unit) =
    ConfigSkippedRoute(viewModel<ConfigOverviewViewModel>(factory = ConfigOverviewViewModel.factory(api)), onBack, onOpen)

/** 已略過: a row opens its file ([onOpen] gets the path). */
@Composable
internal fun ConfigSkippedRoute(viewModel: ConfigOverviewViewModel, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val config = ui.value ?: return ConfigWaitPage(R.string.rv_rules_skipped, onBack, readFailed(ui.failure, viewModel::retry))
    RvConfigSkippedPage(config, onBack) { onOpen(it.source) }
}

@Composable
internal fun ConfigSourceRoute(api: NativeApi, path: String, onBack: () -> Unit, onApplied: () -> Unit) =
    ConfigSourceRoute(viewModel<ConfigSourceViewModel>(key = path, factory = ConfigSourceViewModel.factory(api, path)), onBack, onApplied)

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
internal fun ConfigHistoryRoute(api: NativeApi, onBack: () -> Unit, routes: ConfigRoutes) =
    ConfigHistoryRoute(viewModel<ConfigHistoryViewModel>(factory = ConfigHistoryViewModel.factory(api)), onBack, routes)

/**
 * 版本紀錄; 匯出設定檔 asks first, since the files can carry credentials, and which kind (honk makes the portable one
 * alone: [HONK_EXPORT_KINDS]), then stages honk's export; an export banner's action reopens the sheet.
 */
@Composable
internal fun ConfigHistoryRoute(viewModel: ConfigHistoryViewModel, onBack: () -> Unit, routes: ConfigRoutes, confirmExport: Boolean = false) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val rename by viewModel.rename.collectAsStateWithLifecycle()
    val exported by viewModel.exported.collectAsStateWithLifecycle()
    val notice by viewModel.exportNotice.collectAsStateWithLifecycle()
    var asking by rememberSaveable { mutableStateOf(confirmExport) }
    var kind by rememberSaveable { mutableStateOf(ExportKind.Portable) }
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
            viewModel.export()
        },
        onDismiss = { asking = false },
    )
}

@Composable
internal fun ConfigRevisionRoute(api: NativeApi, rev: Int, onBack: () -> Unit, onRestored: () -> Unit) =
    ConfigRevisionRoute(viewModel<ConfigRevisionViewModel>(key = "rev-$rev", factory = ConfigRevisionViewModel.factory(api, rev)), onBack, onRestored)

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
internal fun ConfigImportRoute(api: NativeApi, text: String, name: String, onBack: () -> Unit, onPickAgain: () -> Unit) =
    ConfigImportRoute(viewModel<ConfigImportViewModel>(key = name, factory = viewModelFactory { initializer { ConfigImportViewModel(api, text, name) } }), onBack, onPickAgain)

/** 匯入設定檔 for a staged pick. */
@Composable
internal fun ConfigImportRoute(viewModel: ConfigImportViewModel, onBack: () -> Unit, onPickAgain: () -> Unit) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val state = ui.value ?: return ConfigWaitPage(R.string.rv_config_import, onBack, readFailed(ui.failure, viewModel::retry))
    RvConfigImportPage(state, onBack, onPickAgain)
}
