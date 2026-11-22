package dev.doona.android.prototype.rv.policy

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import dev.doona.android.R
import dev.doona.android.prototype.rv.RV_NO_VALUE
import dev.doona.android.prototype.rv.Rv
import dev.doona.android.prototype.rv.RvFlag
import dev.doona.android.prototype.rv.RvIconDisc
import dev.doona.android.prototype.rv.RvRow
import dev.doona.android.prototype.rv.RvSize
import dev.doona.android.prototype.rv.RvText
import dev.doona.android.prototype.rv.nodes.SEPARATOR
import dev.doona.android.prototype.rv.rememberRvFormat

/**
 * Where a rule or a picked target sends traffic, as 活動's rule rows draw it: the flag of the node the group uses, or
 * 直連's straight arrow.
 */
@Composable
internal fun RvPolicyTarget(group: RvPolicyGroup?) {
    if (group == null) RvIconDisc(R.drawable.rv_sym_arrow_right_alt) else RvFlag(group.selected.place.name, RvSize.rowDisc)
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
 * after its kind and shows no bytes. [trailing] follows both lines (the drag handle in edit mode).
 */
@Composable
internal fun RvRuleRow(
    rule: RvPolicyRule,
    modifier: Modifier = Modifier,
    end: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val format = rememberRvFormat()
    val subtitle = when (rule.state) {
        RvRuleState.On -> rule.match.kind()
        RvRuleState.Off -> rule.match.kind() + SEPARATOR + stringResource(R.string.rv_policy_rule_off)
        RvRuleState.Skipped -> rule.match.kind() + SEPARATOR + stringResource(R.string.rv_policy_rule_skipped)
    }
    val bytes = if (rule.state == RvRuleState.On) format.bytes(rule.bytes).toString() else RV_NO_VALUE
    RvRow(
        { RvPolicyTarget(rule.group) },
        rule.match.title(),
        subtitle,
        modifier,
        titleEnd = { RvText(rule.targetName(), Rv.type.rowValue, align = TextAlign.End) },
        subtitleEnd = { RvText(end ?: bytes, Rv.type.rowSub, color = Rv.colors.textSecondary, align = TextAlign.End) },
        trailing = trailing,
    )
}

/** Where a rule sends: its group's name, or 直連. */
@Composable
internal fun RvPolicyRule.targetName(): String = group?.let { stringResource(it.name) } ?: stringResource(R.string.mode_direct)

private const val GEOIP = "GEOIP"
private const val GEOSITE = "GEOSITE"
