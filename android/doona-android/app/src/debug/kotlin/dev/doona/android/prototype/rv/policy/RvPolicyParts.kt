package dev.doona.android.prototype.rv.policy

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.doona.android.R
import dev.doona.android.prototype.rv.RvFlag
import dev.doona.android.prototype.rv.RvIconDisc
import dev.doona.android.prototype.rv.RvSize

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
}

/** The kind of match, under the title. */
@Composable
internal fun RvRuleMatch.kind(): String = when (this) {
    is RvRuleMatch.Domains -> stringResource(R.string.rv_policy_kind_domain)
    is RvRuleMatch.GeoIp -> GEOIP
    RvRuleMatch.Lan -> stringResource(R.string.rv_policy_lan_kind)
    RvRuleMatch.Rest -> stringResource(R.string.rv_policy_rest_kind)
}

private const val GEOIP = "GEOIP"
