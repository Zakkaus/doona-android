package dev.doona.android.feature.policies

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.doona.android.R
import dev.doona.android.app.nativeApi
import dev.doona.android.designsystem.rv.RvGutter
import dev.doona.android.designsystem.rv.RvNotice
import dev.doona.android.designsystem.rv.RvNoticeKind
import dev.doona.android.designsystem.rv.RvSourceDiff
import dev.doona.android.feature.config.rvLineDiff
import dev.doona.android.feature.messageRes
import dev.doona.android.feature.model.RvRuleSource

/**
 * 範本 (the 策略 options row) on the app's [nativeApi]: doona's templates, the one honk's routing is marked, and 檢視變更
 * ([onReview], with the chosen template's id) once another is chosen; with rules from no template, the first buildable
 * one starts chosen. The choice is the page's own until the review applies it. A failed read shows as the page's banner
 * with 重試.
 */
@Composable
internal fun TemplateRoute(onBack: () -> Unit, onReview: (String) -> Unit) {
    TemplateRoute(viewModel(factory = TemplateViewModel.factory(nativeApi())), onBack, onReview)
}

/** 範本 on a given [viewModel], for a caller (a test) that makes its own. */
@Composable
internal fun TemplateRoute(viewModel: TemplateViewModel, onBack: () -> Unit, onReview: (String) -> Unit) {
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
 * The review before [templateId] replaces the rules, and its 套用, on the app's [nativeApi]: on success [onApplied] (back
 * to 策略); a failure shows as the page's banner with 重試, which applies again. Until honk's config is read the page
 * waits as its skeleton.
 */
@Composable
internal fun TemplateImpactRoute(templateId: String, onBack: () -> Unit, onApplied: () -> Unit) {
    TemplateImpactRoute(viewModel(factory = TemplateImpactViewModel.factory(nativeApi(), templateId)), onBack, onApplied)
}

/** The template review on a given [viewModel], for a caller (a test) that makes its own. */
@Composable
internal fun TemplateImpactRoute(viewModel: TemplateImpactViewModel, onBack: () -> Unit, onApplied: () -> Unit) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val impact = ui.impact
    val apply = { viewModel.apply(onApplied) }
    val banner = ui.failure?.let {
        val title = if (impact == null) R.string.rv_template_read_failed else R.string.rv_template_apply_failed
        RvNotice(RvNoticeKind.Error, stringResource(title), stringResource(it.messageRes()), stringResource(R.string.rv_retry), if (impact == null) viewModel::load else apply)
    }
    RvTemplateImpactPage(impact, onBack, apply.takeIf { ui.edit?.first?.writable == true }, banner) {
        ui.edit?.let { (source, candidate) ->
            remember(source, candidate) { rvLineDiff(source.path, source.content, candidate) }?.firstOrNull()?.let { diff ->
                RvGutter { RvSourceDiff(diff, onOpen = null) }
            }
        }
    }
}
