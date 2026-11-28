package dev.doona.android.feature.config

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.doona.android.R
import dev.doona.android.designsystem.rv.LocalFrameInsets
import dev.doona.android.designsystem.rv.RV_NO_VALUE
import dev.doona.android.designsystem.rv.RvBannerSlot
import dev.doona.android.designsystem.rv.RvCard
import dev.doona.android.designsystem.rv.RvDiffKind
import dev.doona.android.designsystem.rv.RvLargeTitle
import dev.doona.android.designsystem.rv.RvListEdit
import dev.doona.android.designsystem.rv.RvNotice
import dev.doona.android.designsystem.rv.RvNoticeKind
import dev.doona.android.designsystem.rv.RvPrimaryButton
import dev.doona.android.designsystem.rv.RvRadioRow
import dev.doona.android.designsystem.rv.RvScrollPage
import dev.doona.android.designsystem.rv.RvSearchField
import dev.doona.android.designsystem.rv.RvSettingsNavRow
import dev.doona.android.designsystem.rv.RvSettingsRadioCard
import dev.doona.android.designsystem.rv.RvSheet
import dev.doona.android.designsystem.rv.RvStatRow
import dev.doona.android.engine.EngineException
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
import java.io.ByteArrayOutputStream

internal val PRIMARY_TEMPLATE_IDS = listOf("cn-direct", "gfw", "global")
internal val MORE_TEMPLATE_IDS = listOf("single", "services", "regions", "homebound")

/** Where the configuration pages lead; each is a push of the app's back stack. */
internal class ConfigRoutes(
    val file: (String) -> Unit = {},
    val skipped: () -> Unit = {},
    val effective: () -> Unit = {},
    val history: () -> Unit = {},
    val revision: (Int) -> Unit = {},
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
    LaunchedEffect(viewModel) { viewModel.retry() }
    val imported by viewModel.imported.collectAsStateWithLifecycle()
    val writeFailure by viewModel.writeFailure.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var newPath by rememberSaveable { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val bytes = ByteArrayOutputStream()
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            if (bytes.size() + n > RvConfigLimits.BYTES) throw EngineException(ErrorCode.SourceLimit)
                            bytes.write(buffer, 0, n)
                        }
                        bytes.toString(Charsets.UTF_8.name())
                    }
                }
            }
            viewModel.import(result.getOrNull(), (result.exceptionOrNull() as? EngineException)?.code)
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
        edit = RvListEdit(onAdd = { newPath = "" }, onRemove = viewModel::removeFile),
        // honk-gap:config-create
        addFile = newPath?.let { RvConfigAddFile(it, { path -> newPath = path }, null) },
        banner = readFailed(ui.failure, viewModel::retry) ?: imported?.let { importBanner(it, viewModel::importSeen) { picker.launch(arrayOf("*/*")) } }
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
        stringResource(if (imported.code == null) R.string.rv_config_read_failed else R.string.rv_config_import_refused),
        imported.code?.let { stringResource(it.messageRes()) },
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
    LaunchedEffect(viewModel) { viewModel.retry() }
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val config = ui.value ?: return ConfigWaitPage(R.string.rv_rules_skipped, onBack, readFailed(ui.failure, viewModel::retry))
    RvConfigSkippedPage(config, onBack) { onOpen(it.source) }
}

@Composable
internal fun ConfigSourceRoute(api: NativeApi, path: String, line: Int, onBack: () -> Unit, onApplied: () -> Unit, onFile: (String) -> Unit) =
    ConfigSourceRoute(viewModel<ConfigSourceViewModel>(key = path, factory = ConfigSourceViewModel.factory(api, path, line)), onBack, onApplied, onFile)

/**
 * 原始碼 and, once 檢視變更 passed, its diff over it; Back from the diff returns to the editor with the draft kept, Back
 * from the editor drops the draft. [onApplied] runs once 套用 made a new head.
 */
@Composable
internal fun ConfigSourceRoute(viewModel: ConfigSourceViewModel, onBack: () -> Unit, onApplied: () -> Unit, onFile: (String) -> Unit) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(ui.applied) { if (ui.applied) onApplied() }
    val draft = ui.draft ?: return ConfigWaitPage(R.string.rv_config_source, onBack, readFailed(ui.failure, viewModel::reload))
    val diff = ui.diff
    BackHandler(enabled = diff != null, onBack = viewModel::closeDiff)
    if (diff == null) {
        var picking by rememberSaveable(draft.path) { mutableStateOf(false) }
        var query by remember(draft.path) { mutableStateOf<String?>(null) }
        var searchRequest by remember(draft.path) { mutableIntStateOf(0) }
        RvConfigSourcePage(draft, ui.value, viewModel::onValueChange, onBack, { picking = true }, { query = "" }, viewModel::review, readFailed(ui.failure, viewModel::review), searchRequest)
        RvSheet(picking, { picking = false }, stringResource(R.string.rv_config_files)) {
            RvSettingsRadioCard {
                ui.paths.forEach { path ->
                    RvRadioRow(null, path, path == draft.path, onSelect = {
                        picking = false
                        onFile(path)
                    })
                }
            }
        }
        val search = stringResource(R.string.rv_config_search)
        RvSheet(query != null, { query = null }, search) {
            val term = query.orEmpty()
            val start = if (term.isEmpty()) -1 else ui.value.text.indexOf(term, ignoreCase = true)
            RvSearchField(null, term, { query = it }, hint = search)
            RvPrimaryButton(search, {
                viewModel.onValueChange(ui.value.copy(selection = TextRange(start, start + term.length)))
                searchRequest++
                query = null
            }, enabled = start >= 0)
        }
    } else {
        val failure = ui.failure?.let { RvNotice(RvNoticeKind.Error, stringResource(R.string.rv_config_apply_failed), stringResource(it.messageRes()), stringResource(R.string.rv_config_reload), viewModel::reload) }
        RvConfigDiffPage(diff, viewModel::closeDiff, viewModel::apply, viewModel::reload, onOpen = { path, at ->
            val line = if (!at.base) {
                at.line
            } else {
                val hunk = diff.files.orEmpty().first { it.source == path }.hunks.first { rows -> rows.any { it.at == at } }
                val i = hunk.indexOfFirst { it.at == at }
                (i + 1..hunk.lastIndex).firstNotNullOfOrNull { n -> hunk[n].takeUnless { it.kind == RvDiffKind.Removed }?.line }
                    ?: (i - 1 downTo 0).firstNotNullOfOrNull { n -> hunk[n].takeUnless { it.kind == RvDiffKind.Removed }?.line } ?: 1
            }
            val text = ui.value.text
            val breaks = charArrayOf('\r', '\n')
            var start = 0
            repeat(line - 1) {
                val end = text.indexOfAny(breaks, start)
                start = end + if (text.startsWith("\r\n", end)) 2 else 1
            }
            val end = text.indexOfAny(breaks, start)
            viewModel.onValueChange(ui.value.copy(selection = TextRange(start, if (end < 0) text.length else end)))
            viewModel.closeDiff()
        }, failure = failure)
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
    LaunchedEffect(viewModel) { viewModel.retry() }
    val exported by viewModel.exported.collectAsStateWithLifecycle()
    val notice by viewModel.exportNotice.collectAsStateWithLifecycle()
    var asking by rememberSaveable { mutableStateOf(confirmExport) }
    var kind by rememberSaveable { mutableStateOf(ExportKind.Portable) }
    val resolver = LocalContext.current.contentResolver
    var pickerOpen by rememberSaveable { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        viewModel.exportHandled(uri?.let { { resolver.openOutputStream(it, "wt") } })
        pickerOpen = false
    }
    LaunchedEffect(exported) {
        if (exported != null && !pickerOpen) {
            pickerOpen = true
            picker.launch("honk.dae")
        }
    }
    val history = ui.value?.takeIf { ui.failure == null } ?: return ConfigWaitPage(R.string.rv_config_history, onBack, readFailed(ui.failure, viewModel::retry))
    val banner = notice.failure?.let { changeBanner(stringResource(R.string.rv_config_export_failed), it) { asking = true } } ?: rvExportBanner(
        notice.value,
        onArchive = {
            viewModel.exportNoticeSeen()
            asking = true
        },
        onSeen = viewModel::exportNoticeSeen,
    )
    RvConfigHistoryPage(history.revisions, history.headRev, history.nowEpochMs, onBack, routes.revision, { asking = true }, banner = banner)
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
    val restored by viewModel.restored.collectAsStateWithLifecycle()
    LaunchedEffect(restored) { if (restored) onRestored() }
    val view = ui.value ?: return ConfigWaitPage(R.string.rv_config_history, onBack, readFailed(ui.failure, viewModel::retry))
    val banner = ui.failure?.let { changeBanner(stringResource(R.string.rv_config_restore_failed), it, viewModel::restore) }
    val restore = if (view.revision.rev == view.headRev) null else viewModel::restore
    RvConfigRevisionPage(view.revision, view.files, view.nowEpochMs, onBack, restore, banner = banner)
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
