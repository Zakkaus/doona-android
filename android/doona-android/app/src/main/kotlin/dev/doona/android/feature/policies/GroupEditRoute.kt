package dev.doona.android.feature.policies

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.doona.android.R
import dev.doona.android.app.nativeApi
import dev.doona.android.feature.settings.RvSettingsLoading
import dev.doona.android.feature.settings.changeBanner
import dev.doona.android.feature.settings.readBanner

/**
 * The group editor in the app, on honk's group [groupId]: it leaves ([onBack]) once honk took the change; 名稱 and 成員
 * open the file that writes them ([onOpenSource] with its path); 預設 and 全部不可用時 open their picker in place.
 */
@Composable
internal fun GroupEditRoute(groupId: String, onBack: () -> Unit, onOpenSource: (String) -> Unit) {
    val viewModel: GroupEditViewModel = viewModel(factory = GroupEditViewModel.factory(nativeApi(), groupId))
    GroupEditRoute(viewModel, onBack, onOpenSource)
}

/** The group editor on a given [viewModel], for a caller (a test) that makes its own. */
@Composable
internal fun GroupEditRoute(viewModel: GroupEditViewModel, onBack: () -> Unit, onOpenSource: (String) -> Unit = {}) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    var picking by rememberSaveable { mutableStateOf<RvGroupOption?>(null) }
    LaunchedEffect(ui.done) { if (ui.done) onBack() }
    val title = stringResource(R.string.rv_group_edit_title)
    val read = ui.read
    val draft = ui.draft
    if (read == null || draft == null) {
        return ui.readFailure?.let { RvRuleFormFailed(title, readBanner(it, viewModel::retry), onBack) } ?: RvSettingsLoading()
    }
    val option = picking
    if (option != null) {
        BackHandler { picking = null }
        return RvGroupOptionPage(option, ui.members, draft, viewModel::change, onBack = { picking = null }, outbounds = ui.outbounds)
    }
    RvGroupEditPage(
        ui.name,
        ui.members,
        read,
        draft,
        viewModel::change,
        onBack = onBack,
        onApply = viewModel::apply,
        onOpenSource = { ui.source?.let(onOpenSource) },
        onPick = { picking = it },
        banner = ui.writeFailure?.let { changeBanner(stringResource(R.string.rv_config_apply_failed), it, viewModel::apply) },
    )
}
