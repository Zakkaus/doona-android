package dev.doona.android.feature.policies

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RvNotice
import dev.doona.android.designsystem.rv.RvNoticeKind
import dev.doona.android.engine.Engine
import dev.doona.android.feature.messageRes
import dev.doona.android.feature.model.RvRuleSource

/**
 * 範本 (the 策略 options row): the templates the core lists, the one in use marked, and 檢視變更 ([onReview], with the
 * chosen template's id) once another is chosen; with rules from no template, the first buildable one starts chosen. The
 * choice is the page's own until the review applies it. A failed read shows as the page's banner with 重試.
 */
@Composable
internal fun TemplateRoute(engine: Engine, onBack: () -> Unit, onReview: (String) -> Unit) {
    val viewModel: TemplateViewModel = viewModel(factory = TemplateViewModel.factory(engine))
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val current = (ui.source as? RvRuleSource.Template)?.template
    val templates = ui.templates.orEmpty()
    var picked by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = templates.firstOrNull { it.id == picked } ?: current ?: templates.firstOrNull { it.unsupported == null }
    val banner = ui.failure?.let {
        RvNotice(RvNoticeKind.Error, stringResource(R.string.rv_template_read_failed), stringResource(it.messageRes()), stringResource(R.string.rv_retry), viewModel::retry)
    }
    RvTemplatePage(current, selected, { picked = it.id }, onBack, { selected?.let { onReview(it.id) } }, templates, banner)
}

/**
 * The review before [templateId] replaces the rules, and its 套用: on success [onApplied] (back to 策略); a failure shows
 * as the page's banner with 重試, which applies again. Until the catalog is read (a back stack restored after the process
 * died) the page waits as its skeleton.
 */
@Composable
internal fun TemplateImpactRoute(engine: Engine, templateId: String, onBack: () -> Unit, onApplied: () -> Unit) {
    val viewModel: TemplateImpactViewModel = viewModel(factory = TemplateImpactViewModel.factory(engine, templateId))
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val impact = ui.impact
    val apply = { viewModel.apply(onApplied) }
    val banner = ui.failure?.let {
        RvNotice(RvNoticeKind.Error, stringResource(R.string.rv_template_apply_failed), stringResource(it.messageRes()), stringResource(R.string.rv_retry), apply)
    }
    RvTemplateImpactPage(impact, onBack, apply, banner)
}
