package dev.doona.android.prototype.rv.policy

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import dev.doona.android.R
import dev.doona.android.designsystem.rv.LocalFrameInsets
import dev.doona.android.designsystem.rv.Rv
import dev.doona.android.designsystem.rv.RvAlpha
import dev.doona.android.designsystem.rv.RvCard
import dev.doona.android.designsystem.rv.RvFootnote
import dev.doona.android.designsystem.rv.RvGutter
import dev.doona.android.designsystem.rv.RvIconDisc
import dev.doona.android.designsystem.rv.RvLargeTitle
import dev.doona.android.designsystem.rv.RvPrimaryButton
import dev.doona.android.designsystem.rv.RvRadio
import dev.doona.android.designsystem.rv.RvRow
import dev.doona.android.designsystem.rv.RvScrollPage
import dev.doona.android.designsystem.rv.RvSectionTitle
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.designsystem.rv.RvStatRow
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.designsystem.rv.rvRowSelect
import dev.doona.android.designsystem.rv.rvSystemScheme
import dev.doona.android.prototype.rv.config.rvConfigPhrase
import dev.doona.android.prototype.rv.nodes.SEPARATOR

/**
 * 範本 (the 策略 options row), doona web's routing modes on a phone: one card of templates, each its glyph, its name over
 * one line on what it does, and a radio; the one in use says so after its line, and one the core cannot build yet is
 * listed, dimmed, with the reason after its line, and cannot be chosen. 檢視變更 opens the impact review, and only once a
 * template other than [current] is [selected].
 */
@Composable
internal fun RvTemplatePage(
    current: RvTemplate,
    selected: RvTemplate,
    onSelect: (RvTemplate) -> Unit,
    onBack: () -> Unit,
    onReview: () -> Unit,
) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val title = stringResource(R.string.rv_policy_template)
    RvScrollPage(
        title,
        insets,
        onBack = onBack,
        bottomAction = { RvPrimaryButton(stringResource(R.string.rv_template_review), onReview, enabled = selected.id != current.id) },
    ) { titleBlock ->
        RvLargeTitle(title, titleBlock, insets)
        RvGutter {
            RvCard { RvTemplateData.all.forEach { RvTemplateRow(it, it.id == selected.id, it.id == current.id) { onSelect(it) } } }
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
 * as key stats (the template, rules added, changed and removed, the user's own rules kept in place); the groups it adds
 * and keeps; the rules it adds or changes, each drawn as the rules card draws a rule; a line saying only new
 * connections follow it; and 套用 floating at the bottom.
 */
@Composable
internal fun RvTemplateImpactPage(impact: RvImpact, onBack: () -> Unit, onApply: () -> Unit) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val title = stringResource(R.string.rv_template_review)
    RvScrollPage(
        title,
        insets,
        onBack = onBack,
        bottomAction = { RvPrimaryButton(stringResource(R.string.rv_policy_apply), onApply) },
    ) { titleBlock ->
        RvLargeTitle(title, titleBlock, insets)
        RvGutter { SummaryCard(impact) }
        RvSectionTitle(stringResource(R.string.rv_policy_groups), Modifier.padding(horizontal = RvSpace.gutter))
        RvGutter { RvCard { impact.groups.forEach { ImpactGroupRow(it) } } }
        RvSectionTitle(stringResource(R.string.rv_policy_rules), Modifier.padding(horizontal = RvSpace.gutter))
        RvGutter { RvCard { impact.rules.forEach { ImpactRuleRow(it) } } }
        RvFootnote(stringResource(R.string.rv_template_new_flows))
    }
}

@Composable
private fun SummaryCard(impact: RvImpact) {
    RvCard {
        RvStatRow(stringResource(R.string.rv_policy_template), stringResource(R.string.rv_template_from_to, stringResource(impact.from.name), stringResource(impact.to.name)))
        RvStatRow(stringResource(R.string.rv_template_added), rules(impact.added))
        RvStatRow(stringResource(R.string.rv_template_changed), rules(impact.changed))
        RvStatRow(stringResource(R.string.rv_template_removed), rules(impact.removed))
        RvStatRow(stringResource(R.string.rv_template_kept), rules(impact.kept))
    }
}

@Composable
private fun rules(n: Int) = pluralStringResource(R.plurals.rv_rules_count, n, n)

/** A group the template brings or keeps: its kind's glyph, its name over what happens, how it picks and its size. */
@Composable
private fun ImpactGroupRow(group: RvImpactGroup) {
    val count = pluralStringResource(R.plurals.rv_policy_node_count, group.nodes, group.nodes)
    val line = listOf(stringResource(group.change.label), stringResource(group.kind.label), count).joinToString(SEPARATOR)
    RvRow({ RvIconDisc(group.kind.glyph) }, stringResource(group.name), line)
}

/** A rule the template adds or changes: as the rules card draws it, with what happens in place of its bytes. */
@Composable
private fun ImpactRuleRow(rule: RvImpactRule) {
    val end = rule.was?.let { stringResource(rule.change.label) + SEPARATOR + stringResource(it) } ?: stringResource(rule.change.label)
    RvRuleRow(rule.rule, end = end)
}

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RvTemplatePagePreview() {
    val dark = isSystemInDarkTheme()
    var selected by remember { mutableStateOf(RvTemplateData.cnDirect) }
    RvTheme(rvSystemScheme(dark), dark) { RvTemplatePage(RvTemplateData.cnDirect, selected, { selected = it }, onBack = {}, onReview = {}) }
}

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RvTemplateImpactPagePreview() {
    val dark = isSystemInDarkTheme()
    RvTheme(rvSystemScheme(dark), dark) { RvTemplateImpactPage(RvTemplateData.impact, onBack = {}, onApply = {}) }
}
