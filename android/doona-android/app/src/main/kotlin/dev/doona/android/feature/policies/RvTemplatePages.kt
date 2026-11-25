package dev.doona.android.feature.policies

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.doona.android.R
import dev.doona.android.designsystem.rv.LocalFrameInsets
import dev.doona.android.designsystem.rv.RvAlpha
import dev.doona.android.designsystem.rv.RvCard
import dev.doona.android.designsystem.rv.RvFootnote
import dev.doona.android.designsystem.rv.RvGutter
import dev.doona.android.designsystem.rv.RvIconDisc
import dev.doona.android.designsystem.rv.RvLargeTitle
import dev.doona.android.designsystem.rv.RvNotice
import dev.doona.android.designsystem.rv.RvPrimaryButton
import dev.doona.android.designsystem.rv.RvRadio
import dev.doona.android.designsystem.rv.RvRow
import dev.doona.android.designsystem.rv.RvScrollPage
import dev.doona.android.designsystem.rv.RvSectionTitle
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.designsystem.rv.RvStatRow
import dev.doona.android.designsystem.rv.SEPARATOR
import dev.doona.android.designsystem.rv.rvConfigPhrase
import dev.doona.android.designsystem.rv.rvRowSelect
import dev.doona.android.feature.model.RvImpact
import dev.doona.android.feature.model.RvImpactGroup
import dev.doona.android.feature.model.RvImpactRule
import dev.doona.android.feature.model.RvTemplate
import dev.doona.android.feature.model.text

/**
 * 範本 (the 策略 options row), doona web's routing modes on a phone: one card of templates, each its glyph, its name over
 * one line on what it does, and a radio; the one in use says so after its line, and one the core cannot build yet is
 * listed, dimmed, with the reason after its line, and cannot be chosen. 檢視變更 opens the impact review, and only once a
 * template other than [current] is [selected]; [current] is null when the rules come from no template. The card lists
 * [templates].
 */
@Composable
internal fun RvTemplatePage(
    current: RvTemplate?,
    selected: RvTemplate,
    onSelect: (RvTemplate) -> Unit,
    onBack: () -> Unit,
    onReview: () -> Unit,
    templates: List<RvTemplate>,
) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val title = stringResource(R.string.rv_policy_template)
    RvScrollPage(
        title,
        insets,
        onBack = onBack,
        bottomAction = { RvPrimaryButton(stringResource(R.string.rv_template_review), onReview, enabled = selected.id != current?.id) },
    ) { titleBlock ->
        RvLargeTitle(title, titleBlock, insets)
        RvGutter {
            RvCard { templates.forEach { RvTemplateRow(it, it.id == selected.id, it.id == current?.id) { onSelect(it) } } }
        }
    }
}

/**
 * One template; after its line, why it cannot be chosen, or that it is in use. Also the configuration's template page,
 * which keeps words and the end of each text together ([kept]: [rvConfigPhrase]) as its other pages do.
 */
@Composable
internal fun RvTemplateRow(template: RvTemplate, selected: Boolean, inUse: Boolean, kept: Boolean = false, onSelect: () -> Unit) {
    val reason = template.unsupported
    val state = reason?.let { stringResource(it) } ?: stringResource(R.string.rv_template_in_use).takeIf { inUse }
    val parts = listOfNotNull(stringResource(template.line), state)
    val line = if (kept) rvConfigPhrase(parts) else parts.joinToString(SEPARATOR)
    val title = stringResource(template.name).let { if (kept) rvConfigPhrase(listOf(it)) else it }
    val pick = Modifier
        .graphicsLayer { alpha = if (reason == null) 1f else RvAlpha.DISABLED }
        .rvRowSelect(selected, onSelect, enabled = reason == null)
    RvRow({ RvIconDisc(template.glyph) }, title, line, pick, endFirstLine = true) { RvRadio(selected) }
}

/**
 * The review before a template replaces the rules (doona web's impact dialog), in node detail's grammar: what changes
 * as key stats (the source and the template, rules added, changed and removed, the user's own rules kept in place; a
 * count the core does not tell is left out); the groups it adds and keeps and the rules it adds or changes, each drawn as
 * the rules card draws a rule, where the core tells them; a line saying only new connections follow it; and 套用
 * floating at the bottom. A failed 套用 shows as [banner] above the stats.
 */
@Composable
internal fun RvTemplateImpactPage(impact: RvImpact, onBack: () -> Unit, onApply: () -> Unit, banner: RvNotice? = null) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val title = stringResource(R.string.rv_template_review)
    RvScrollPage(
        title,
        insets,
        onBack = onBack,
        bottomAction = { RvPrimaryButton(stringResource(R.string.rv_policy_apply), onApply) },
    ) { titleBlock ->
        RvLargeTitle(title, titleBlock, insets)
        RvGutter(banner = banner) { SummaryCard(impact) }
        if (impact.groups.isNotEmpty()) {
            RvSectionTitle(stringResource(R.string.rv_policy_groups), Modifier.padding(horizontal = RvSpace.gutter))
            RvGutter { RvCard { impact.groups.forEach { ImpactGroupRow(it) } } }
        }
        if (impact.rules.isNotEmpty()) {
            RvSectionTitle(stringResource(R.string.rv_policy_rules), Modifier.padding(horizontal = RvSpace.gutter))
            RvGutter { RvCard { impact.rules.forEach { ImpactRuleRow(it) } } }
        }
        RvFootnote(stringResource(R.string.rv_template_new_flows))
    }
}

@Composable
private fun SummaryCard(impact: RvImpact) {
    RvCard {
        RvStatRow(stringResource(R.string.rv_policy_template), stringResource(R.string.rv_template_from_to, impact.from.text(), stringResource(impact.to.name)))
        impact.added?.let { RvStatRow(stringResource(R.string.rv_template_added), rules(it)) }
        impact.changed?.let { RvStatRow(stringResource(R.string.rv_template_changed), rules(it)) }
        impact.removed?.let { RvStatRow(stringResource(R.string.rv_template_removed), rules(it)) }
        impact.kept?.let { RvStatRow(stringResource(R.string.rv_template_kept), rules(it)) }
    }
}

@Composable
private fun rules(n: Int) = pluralStringResource(R.plurals.rv_rules_count, n, n)

/** A group the template brings or keeps: its kind's glyph, its name over what happens, how it picks and its size. */
@Composable
private fun ImpactGroupRow(group: RvImpactGroup) {
    val count = pluralStringResource(R.plurals.rv_policy_node_count, group.nodes, group.nodes)
    val line = listOf(stringResource(group.change.label), stringResource(group.kind.label), count).joinToString(SEPARATOR)
    RvRow({ RvIconDisc(group.kind.glyph) }, group.name.text(), line)
}

/** A rule the template adds or changes: as the rules card draws it, with what happens in place of its bytes. */
@Composable
private fun ImpactRuleRow(rule: RvImpactRule) {
    val end = rule.was?.let { stringResource(rule.change.label) + SEPARATOR + stringResource(it) } ?: stringResource(rule.change.label)
    RvRuleRow(rule.rule, end = end)
}
