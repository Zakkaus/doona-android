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
import dev.doona.android.feature.model.RV_TEMPLATES
import dev.doona.android.feature.model.RvRuleSource

/**
 * 範本 (the 策略 options row): the templates the core builds, the one in use marked, and 檢視變更 ([onReview], with the
 * chosen template's id) once another is chosen. The choice is the page's own until the review applies it.
 */
@Composable
internal fun TemplateRoute(engine: Engine, onBack: () -> Unit, onReview: (String) -> Unit) {
    val viewModel: TemplateViewModel = viewModel(factory = TemplateViewModel.factory(engine))
    val source by viewModel.source.collectAsStateWithLifecycle()
    val current = (source as? RvRuleSource.Template)?.template
    var picked by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = RV_TEMPLATES.firstOrNull { it.id == picked } ?: current ?: RV_TEMPLATES.first()
    RvTemplatePage(current, selected, { picked = it.id }, onBack, { onReview(selected.id) }, RV_TEMPLATES)
}

/**
 * The review before [templateId] replaces the rules, and its 套用: on success [onApplied] (back to 策略); a failure shows
 * as the page's banner with 重試, which applies again.
 */
@Composable
internal fun TemplateImpactRoute(engine: Engine, templateId: String, onBack: () -> Unit, onApplied: () -> Unit) {
    val viewModel: TemplateImpactViewModel = viewModel(factory = TemplateImpactViewModel.factory(engine, templateId))
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val impact = ui.impact ?: return
    val apply = { viewModel.apply(onApplied) }
    val banner = ui.failure?.let {
        RvNotice(RvNoticeKind.Error, stringResource(R.string.rv_template_apply_failed), stringResource(it.messageRes()), stringResource(R.string.rv_retry), apply)
    }
    RvTemplateImpactPage(impact, onBack, apply, banner)
}
