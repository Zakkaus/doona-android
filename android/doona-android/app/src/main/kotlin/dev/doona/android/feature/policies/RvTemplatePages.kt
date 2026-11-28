package dev.doona.android.feature.policies

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.doona.android.R
import dev.doona.android.designsystem.rv.LocalFrameInsets
import dev.doona.android.designsystem.rv.RV_NO_VALUE
import dev.doona.android.designsystem.rv.RvBannerSlot
import dev.doona.android.designsystem.rv.RvCard
import dev.doona.android.designsystem.rv.RvCardHeader
import dev.doona.android.designsystem.rv.RvFootnote
import dev.doona.android.designsystem.rv.RvGutter
import dev.doona.android.designsystem.rv.RvIconDisc
import dev.doona.android.designsystem.rv.RvLargeTitle
import dev.doona.android.designsystem.rv.RvNotice
import dev.doona.android.designsystem.rv.RvPrimaryButton
import dev.doona.android.designsystem.rv.RvRadioRow
import dev.doona.android.designsystem.rv.RvRow
import dev.doona.android.designsystem.rv.RvScrollPage
import dev.doona.android.designsystem.rv.RvSectionTitle
import dev.doona.android.designsystem.rv.RvSkeletonCard
import dev.doona.android.designsystem.rv.RvSkeletonEnd
import dev.doona.android.designsystem.rv.RvSkeletonFade
import dev.doona.android.designsystem.rv.RvSkeletonRow
import dev.doona.android.designsystem.rv.RvSkeletonSection
import dev.doona.android.designsystem.rv.RvSkeletonStatRow
import dev.doona.android.designsystem.rv.RvSkeletonText
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.designsystem.rv.RvStatRow
import dev.doona.android.designsystem.rv.SEPARATOR
import dev.doona.android.designsystem.rv.rvConfigPhrase
import dev.doona.android.designsystem.rv.rvSkeletonRowsToFill
import dev.doona.android.feature.model.RvImpact
import dev.doona.android.feature.model.RvImpactGroup
import dev.doona.android.feature.model.RvImpactRule
import dev.doona.android.feature.model.RvTemplate
import dev.doona.android.feature.model.text

/**
 * 範本 (the 策略 options row), doona web's routing modes on a phone: one card of templates, each its glyph, its name over
 * one line on what it does, and a radio; the one in use says so after its line, and one the core cannot build yet is
 * listed, dimmed, with the reason after its line, and cannot be chosen. 檢視變更 opens the impact review, and only once a
 * template other than [current] is [selected]; [current] is null when the rules come from no template, and [selected]
 * when nothing is chosen yet. The card lists [templates]; a failed read shows as [banner] above it.
 */
@Composable
internal fun RvTemplatePage(
    current: RvTemplate?,
    selected: RvTemplate?,
    onSelect: (RvTemplate) -> Unit,
    onBack: () -> Unit,
    onReview: () -> Unit,
    templates: List<RvTemplate>,
    banner: RvNotice? = null,
) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val title = stringResource(R.string.rv_policy_template)
    RvScrollPage(
        title,
        insets,
        onBack = onBack,
        bottomAction = { RvPrimaryButton(stringResource(R.string.rv_template_review), onReview, enabled = selected != null && selected.id != current?.id) },
    ) { titleBlock ->
        RvLargeTitle(title, titleBlock, insets)
        RvGutter(banner = banner) {
            if (templates.isNotEmpty()) RvCard { templates.forEach { RvTemplateRow(it, it.id == selected?.id, it.id == current?.id) { onSelect(it) } } }
        }
    }
}

/**
 * One template; after its line, why it cannot be chosen, or that it is in use. Also the configuration's template page,
 * which keeps words and the end of each text together ([kept]: [rvConfigPhrase]) as its other pages do.
 */
@Composable
internal fun RvTemplateRow(template: RvTemplate, selected: Boolean, inUse: Boolean, kept: Boolean = false, onSelect: (() -> Unit)?) {
    val reason = template.unsupported
    val state = reason?.let { stringResource(it) } ?: stringResource(R.string.rv_template_in_use).takeIf { inUse }
    val parts = listOfNotNull(template.line?.let { stringResource(it) }, state)
    val line = if (kept) rvConfigPhrase(parts) else parts.joinToString(SEPARATOR)
    val title = template.name.text().let { if (kept) rvConfigPhrase(listOf(it)) else it }
    RvRadioRow(template.glyph, title, selected, line, enabled = reason == null && onSelect != null, onSelect = { onSelect?.invoke() })
}

/**
 * The review before a template replaces the rules (doona web's impact dialog), in node detail's grammar: what changes
 * as key stats (the source and the template, rules added, changed and removed, the user's own rules kept in place);
 * the groups and rules it changes, with a dash where the preview has no data; and 套用
 * floating at the bottom. A failed 套用 shows as [banner] above the stats. Before the rules are read ([impact] null: a
 * back stack restored after the process died) the page's skeleton stands in ([ImpactSkeleton]), 套用 is off and the line
 * under the rules waits for them; the page fades in over the skeleton.
 */
@Composable
internal fun RvTemplateImpactPage(impact: RvImpact?, onBack: () -> Unit, onApply: (() -> Unit)?, banner: RvNotice? = null, source: @Composable () -> Unit = {}) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val title = stringResource(R.string.rv_template_review)
    RvScrollPage(
        title,
        insets,
        onBack = onBack,
        bottomAction = { RvPrimaryButton(stringResource(R.string.rv_policy_apply), onApply ?: {}, enabled = impact != null && onApply != null) },
    ) { titleBlock ->
        RvLargeTitle(title, titleBlock, insets)
        RvBannerSlot(banner)
        RvSkeletonFade(impact == null, { Column(Modifier.fillMaxWidth()) { ImpactSkeleton() } }) {
            Column(Modifier.fillMaxWidth()) {
                impact?.let {
                    ImpactBody(it)
                    source()
                }
            }
        }
    }
}

/** The review's stats, the groups and rules the core tells, and the line saying only new connections follow it. */
@Composable
private fun ImpactBody(impact: RvImpact) {
    RvGutter { SummaryCard(impact) }
    // honk-gap:template-impact
    RvSectionTitle(stringResource(R.string.rv_policy_groups), Modifier.padding(horizontal = RvSpace.gutter))
    RvGutter {
        RvCard { if (impact.groups.isEmpty()) RvCardHeader(RV_NO_VALUE) else impact.groups.forEach { ImpactGroupRow(it) } }
    }
    RvSectionTitle(stringResource(R.string.rv_policy_rules), Modifier.padding(horizontal = RvSpace.gutter))
    RvGutter {
        RvCard { if (impact.rules.isEmpty()) RvCardHeader(RV_NO_VALUE) else impact.rules.forEach { ImpactRuleRow(it) } }
    }
    RvFootnote(stringResource(R.string.rv_template_new_flows))
}

/**
 * The review before its rules are read, shaped as the page: the stats card's rows under their labels, then 策略群組 and
 * 分流規則, each a card of rows as the page draws a group and a rule; the rules run past the window's foot, as 全部規則's
 * skeleton does. The line under the rules is not drawn, as it says what the rules do.
 */
@Composable
private fun ImpactSkeleton() {
    val template = stringResource(R.string.rv_policy_template)
    val stats = listOf(R.string.rv_template_added, R.string.rv_template_changed, R.string.rv_template_removed, R.string.rv_template_kept)
    RvGutter {
        RvSkeletonCard(description = template) {
            RvSkeletonStatRow(template, RvSkeletonText.Change)
            stats.forEach { RvSkeletonStatRow(stringResource(it)) }
        }
    }
    val groups = stringResource(R.string.rv_policy_groups)
    RvSkeletonSection(groups, Modifier.padding(horizontal = RvSpace.gutter))
    RvGutter { RvSkeletonCard(description = groups) { repeat(SKELETON_GROUPS) { RvSkeletonRow(it, RvSkeletonText.Mixed) } } }
    val rules = stringResource(R.string.rv_policy_rules)
    val rows = rvSkeletonRowsToFill()
    RvSkeletonSection(rules, Modifier.padding(horizontal = RvSpace.gutter))
    RvGutter { RvSkeletonCard(description = rules) { repeat(rows) { RvSkeletonRow(it, RvSkeletonText.Words, RvSkeletonEnd.Node) } } }
}

/** Group rows in the skeleton: the count is not known before the read, so the page draws as many as a sample template brings. */
private const val SKELETON_GROUPS = 4

@Composable
private fun SummaryCard(impact: RvImpact) {
    RvCard {
        RvStatRow(stringResource(R.string.rv_policy_template), stringResource(R.string.rv_template_from_to, impact.from.text(), impact.to.name.text()))
        RvStatRow(stringResource(R.string.rv_template_added), rules(impact.added))
        RvStatRow(stringResource(R.string.rv_template_changed), rules(impact.changed))
        RvStatRow(stringResource(R.string.rv_template_removed), rules(impact.removed))
        RvStatRow(stringResource(R.string.rv_template_kept), rules(impact.kept))
    }
}

@Composable
private fun rules(n: Int?) = if (n == null) RV_NO_VALUE else pluralStringResource(R.plurals.rv_rules_count, n, n)

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
