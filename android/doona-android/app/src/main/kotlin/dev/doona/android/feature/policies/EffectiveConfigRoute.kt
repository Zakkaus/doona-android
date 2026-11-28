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
import dev.doona.android.engine.Engine
import dev.doona.android.feature.messageRes
import dev.doona.android.feature.model.RvPolicyState
import dev.doona.android.share.rememberEffectiveConfigActions

/**
 * 檢視生效設定 (from 全部規則): builds the ViewModel and draws the page from its state while the screen is started. 複製
 * and 匯出 hand on the core's text; [onRules] opens 全部規則, [onTrace] 路由測試. A read that failed shows its banner with
 * 重試.
 */
@Composable
internal fun EffectiveConfigRoute(engine: Engine, onBack: () -> Unit, onRules: () -> Unit, onTrace: () -> Unit) {
    val viewModel: EffectiveConfigViewModel = viewModel(factory = EffectiveConfigViewModel.factory(engine, nativeApi()))
    EffectiveConfigRoute(viewModel, onBack, onRules, onTrace)
}

/** 生效設定 on a given [viewModel], for a caller (a test) that makes its own. */
@Composable
internal fun EffectiveConfigRoute(viewModel: EffectiveConfigViewModel, onBack: () -> Unit, onRules: () -> Unit, onTrace: () -> Unit = {}) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val actions = rememberEffectiveConfigActions(stringResource(R.string.rv_effective_title), ui.text)
    val banner = ui.failure?.let {
        RvNotice(RvNoticeKind.Error, stringResource(R.string.rv_effective_failed), stringResource(it.messageRes()), stringResource(R.string.rv_retry), viewModel::retry)
    }
    RvEffectiveConfigPage(RvPolicyState(mode = ui.mode), onBack, onRules, actions.copy, actions.export, ui.truncated, banner, ui.config, onTrace)
}
