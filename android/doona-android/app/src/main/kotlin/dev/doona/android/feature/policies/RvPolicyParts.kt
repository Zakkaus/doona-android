package dev.doona.android.feature.policies

import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RV_NO_VALUE
import dev.doona.android.designsystem.rv.Rv
import dev.doona.android.designsystem.rv.RvFlag
import dev.doona.android.designsystem.rv.RvIconDisc
import dev.doona.android.designsystem.rv.RvRow
import dev.doona.android.designsystem.rv.RvRowEndName
import dev.doona.android.designsystem.rv.RvRowRemove
import dev.doona.android.designsystem.rv.RvSize
import dev.doona.android.designsystem.rv.RvText
import dev.doona.android.designsystem.rv.SEPARATOR
import dev.doona.android.designsystem.rv.latencyColor
import dev.doona.android.designsystem.rv.latencyText
import dev.doona.android.designsystem.rv.rememberRvFormat
import dev.doona.android.designsystem.rv.rvRemoveTrailing
import dev.doona.android.feature.model.RV_BLOCK
import dev.doona.android.feature.model.RV_DIRECT
import dev.doona.android.feature.model.RvNode
import dev.doona.android.feature.model.RvPolicyGroup
import dev.doona.android.feature.model.RvPolicyRule
import dev.doona.android.feature.model.RvRuleEnd
import dev.doona.android.feature.model.RvRuleMatch
import dev.doona.android.feature.model.RvRuleSource
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

/** Where the rules come from, as 策略 and the template review name it: the template, the subscription, or 主設定檔. */
@Composable
internal fun RvRuleSource.text(): String = when (this) {
    is RvRuleSource.Template -> template.name.text()
    is RvRuleSource.Subscription -> name
    RvRuleSource.File -> stringResource(R.string.rv_config_entry)
}

/** The kind of match, under the title. */
@Composable
internal fun RvRuleMatch.kind(): String = when (this) {
    is RvRuleMatch.Domains -> stringResource(R.string.rv_policy_kind_domain)
    is RvRuleMatch.GeoIp -> stringResource(R.string.rv_rule_geoip)
    RvRuleMatch.Lan -> stringResource(R.string.rv_policy_lan_kind)
    RvRuleMatch.Rest -> stringResource(R.string.rv_policy_rest_kind)
    is RvRuleMatch.Value -> stringResource(kind.label)
    is RvRuleMatch.GeoSite -> stringResource(R.string.rv_rule_geosite)
}

/**
 * A rule as the 策略 card and the list of all rules draw it, after 活動's rule rows: where the traffic ends, what it
 * matches over its kind, and the target over [end] (today's bytes by default). A rule that is off or skipped says so
 * after its kind and shows no bytes.
 */
@Composable
internal fun RvRuleRow(rule: RvPolicyRule, modifier: Modifier = Modifier, end: String? = null, remove: RvRowRemove? = null) {
    val format = rememberRvFormat()
    val subtitle = when (rule.state) {
        RvRuleState.On -> rule.match.kind()
        RvRuleState.Off -> rule.match.kind() + SEPARATOR + stringResource(R.string.rv_policy_rule_off)
        RvRuleState.Skipped -> rule.match.kind() + SEPARATOR + stringResource(R.string.rv_policy_rule_skipped)
    }
    val bytes = if (rule.state == RvRuleState.On) format.bytes(rule.bytes).toString() else RV_NO_VALUE
    val title = rule.match.title()
    val removeLabel = stringResource(R.string.rv_list_remove, title)
    RvRow(
        { RvRuleEndMark(rule) },
        title,
        subtitle,
        modifier,
        titleEnd = { RvRowEndName(rule.targetName()) },
        subtitleEnd = { RvText(end ?: bytes, Rv.type.rowSub, color = Rv.colors.textSecondary, align = TextAlign.End) },
        titleKeepWords = false,
        trailing = rvRemoveTrailing(remove, removeLabel),
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
 * A group as a row, on 策略's groups card and among a group page's members: its kind's [glyph] (flagged while [current]),
 * its [name] over its [line], and at the end the [node] its choice ends on with that node's latency; nothing there when
 * the choice leads to no node. With [remove] (an editable list) the row ends with the remove control, read as [removeLabel].
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
    remove: RvRowRemove? = null,
    removeLabel: String = name,
) {
    RvRow(
        { RvIconDisc(glyph, current = current) },
        name,
        line,
        modifier,
        titleEnd = node?.let { { RvRowEndName(it.name) } },
        subtitleEnd = node?.let { { RvText(latencyText(it.latencyMs), Rv.type.rowSub, color = latencyColor(it.latencyMs), align = TextAlign.End) } },
        trailing = rvRemoveTrailing(remove, removeLabel) ?: trailing,
        titleKeepWords = false,
    )
}
