package dev.doona.android.feature.policies

import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RV_NO_VALUE
import dev.doona.android.designsystem.rv.RV_ROW_TITLE_LINES
import dev.doona.android.designsystem.rv.Rv
import dev.doona.android.designsystem.rv.RvCard
import dev.doona.android.designsystem.rv.RvFlag
import dev.doona.android.designsystem.rv.RvIconDisc
import dev.doona.android.designsystem.rv.RvRadio
import dev.doona.android.designsystem.rv.RvRow
import dev.doona.android.designsystem.rv.RvSeeAll
import dev.doona.android.designsystem.rv.RvSize
import dev.doona.android.designsystem.rv.RvText
import dev.doona.android.designsystem.rv.SEPARATOR
import dev.doona.android.designsystem.rv.latencyColor
import dev.doona.android.designsystem.rv.latencyText
import dev.doona.android.designsystem.rv.rememberRvFormat
import dev.doona.android.designsystem.rv.rvRowSelect
import dev.doona.android.feature.model.RV_DIRECT
import dev.doona.android.feature.model.RvNode
import dev.doona.android.feature.model.RvPolicyGroup
import dev.doona.android.feature.model.RvPolicyRule
import dev.doona.android.feature.model.RvRuleEnd
import dev.doona.android.feature.model.RvRuleMatch
import dev.doona.android.feature.model.RvRuleState
import dev.doona.android.feature.model.text

/**
 * Where a rule or a picked target sends traffic, as 活動's rule rows draw it: the flag of the node the group uses (its
 * kind's glyph when its choice leads to no node), or 直連's straight arrow.
 */
@Composable
internal fun RvPolicyTarget(group: RvPolicyGroup?) {
    val node = group?.selected
    when {
        group == null -> RvIconDisc(R.drawable.rv_sym_arrow_right_alt)
        node == null -> RvIconDisc(group.kind.glyph)
        else -> RvFlag(node.place.flag, RvSize.rowDisc)
    }
}

/** What a rule matches, as its row's title. */
@Composable
internal fun RvRuleMatch.title(): String = when (this) {
    is RvRuleMatch.Domains -> pluralStringResource(R.plurals.rv_policy_domains, count, first, count)
    is RvRuleMatch.GeoIp -> code
    RvRuleMatch.Lan -> stringResource(R.string.rv_policy_lan)
    RvRuleMatch.Rest -> stringResource(R.string.rv_policy_rest)
    is RvRuleMatch.Value -> value
    is RvRuleMatch.GeoSite -> category
}

/** The kind of match, under the title. */
@Composable
internal fun RvRuleMatch.kind(): String = when (this) {
    is RvRuleMatch.Domains -> stringResource(R.string.rv_policy_kind_domain)
    is RvRuleMatch.GeoIp -> GEOIP
    RvRuleMatch.Lan -> stringResource(R.string.rv_policy_lan_kind)
    RvRuleMatch.Rest -> stringResource(R.string.rv_policy_rest_kind)
    is RvRuleMatch.Value -> stringResource(kind.label)
    is RvRuleMatch.GeoSite -> GEOSITE
}

/**
 * A rule as the 策略 card and the list of all rules draw it, after 活動's rule rows: where the traffic ends, what it
 * matches over its kind, and the target over [end] (today's bytes by default). A rule that is off or skipped says so
 * after its kind and shows no bytes.
 */
@Composable
internal fun RvRuleRow(rule: RvPolicyRule, modifier: Modifier = Modifier, end: String? = null) {
    val format = rememberRvFormat()
    val subtitle = when (rule.state) {
        RvRuleState.On -> rule.match.kind()
        RvRuleState.Off -> rule.match.kind() + SEPARATOR + stringResource(R.string.rv_policy_rule_off)
        RvRuleState.Skipped -> rule.match.kind() + SEPARATOR + stringResource(R.string.rv_policy_rule_skipped)
    }
    val bytes = if (rule.state == RvRuleState.On) format.bytes(rule.bytes).toString() else RV_NO_VALUE
    RvRow(
        { RvRuleEndMark(rule) },
        rule.match.title(),
        subtitle,
        modifier,
        titleEnd = { RvText(rule.targetName(), Rv.type.rowValue, align = TextAlign.End) },
        subtitleEnd = { RvText(end ?: bytes, Rv.type.rowSub, color = Rv.colors.textSecondary, align = TextAlign.End) },
        titleKeepWords = false,
    )
}

/** Where a rule's flows end: its group's mark ([RvPolicyTarget]), 拒絕's block mark, or an error mark for a missing group. */
@Composable
private fun RvRuleEndMark(rule: RvPolicyRule) {
    when {
        rule.group != null || rule.end == RvRuleEnd.Direct -> RvPolicyTarget(rule.group)
        rule.end == RvRuleEnd.Reject -> RvIconDisc(R.drawable.rv_sym_block)
        else -> RvIconDisc(R.drawable.rv_sym_error)
    }
}

/** Where a rule sends: its group's name, 直連, 拒絕, or that its group is missing. */
@Composable
internal fun RvPolicyRule.targetName(): String = group?.name?.text() ?: stringResource(
    when (end) {
        RvRuleEnd.Direct -> R.string.mode_direct
        RvRuleEnd.Reject -> R.string.rv_policy_reject
        RvRuleEnd.Missing -> R.string.rv_policy_target_missing
    },
)

/**
 * The 送往 list of the quick rule and the rule page: the [groups] a rule can send to, then 直連: the first [TARGETS]
 * groups, then the rest in place. A group is drawn by
 * where its traffic ends, as on 活動 and in the rules card.
 */
@Composable
internal fun RvTargetCard(groups: List<RvPolicyGroup>, targetId: String, onTarget: (String) -> Unit) {
    var all by rememberSaveable { mutableStateOf(false) }
    val shown = if (all) groups else groups.take(TARGETS)
    RvCard {
        shown.forEach { Target(it, targetId == it.id) { onTarget(it.id) } }
        Target(null, targetId == RV_DIRECT) { onTarget(RV_DIRECT) }
        if (!all) {
            val more = groups.size - shown.size
            RvSeeAll(pluralStringResource(R.plurals.rv_policy_other_groups, more, more)) { all = true }
        }
    }
}

/** One destination: the group and the node it uses, or 直連; the whole row picks it. */
@Composable
private fun Target(group: RvPolicyGroup?, selected: Boolean, onSelect: () -> Unit) {
    val pick = Modifier.rvRowSelect(selected, onSelect)
    RvRow(
        { RvPolicyTarget(group) },
        group?.name?.text() ?: stringResource(R.string.mode_direct),
        if (group == null) stringResource(R.string.rv_policy_no_proxy) else group.selected?.name ?: stringResource(group.kind.label),
        pick,
        titleKeepWords = false,
        subtitleKeepWords = false,
        endFirstLine = true,
    ) { RvRadio(selected) }
}

/** The groups listed before 其他 N 個策略組: the main node, 自動, 串流 and AI. */
private const val TARGETS = 4

private const val GEOIP = "GEOIP"
private const val GEOSITE = "GEOSITE"

/**
 * A group as a row, on 策略's groups card and among a group page's members: its kind's [glyph] (flagged while [current]),
 * its [name] over its [line], and at the end the [node] its choice ends on with that node's latency; nothing there when
 * the choice leads to no node.
 */
@Composable
internal fun RvGroupRow(
    @DrawableRes glyph: Int,
    name: String,
    line: String,
    node: RvNode?,
    modifier: Modifier,
    current: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    RvRow(
        { RvIconDisc(glyph, current = current) },
        name,
        line,
        modifier,
        titleEnd = node?.let { { RvText(it.name, Rv.type.rowValue, align = TextAlign.End, maxLines = RV_ROW_TITLE_LINES, keepWords = false) } },
        subtitleEnd = node?.let { { RvText(latencyText(it.latencyMs), Rv.type.rowSub, color = latencyColor(it.latencyMs), align = TextAlign.End) } },
        trailing = trailing,
        titleKeepWords = false,
    )
}
