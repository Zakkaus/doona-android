package dev.doona.android.feature.model

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import dev.doona.android.R

/** A policy group: its id, its name as the profile gives it, how it picks, its nodes and the one it uses now. */
@Immutable
internal class RvPolicyGroup(val id: String, @StringRes val name: Int, val kind: RvGroupKind, val members: List<RvNode>, val selectedId: String) {
    val selected: RvNode = members.first { it.id == selectedId }

    /** The members fastest first by their last test; a node whose last test timed out comes last. */
    val byLatency: List<RvNode> = members.sortedWith(compareBy(nullsLast()) { it.latencyMs })

    fun choosing(nodeId: String) = RvPolicyGroup(id, name, kind, members, nodeId)
}

/** What a rule matches. */
internal sealed interface RvRuleMatch {
    /** A set of domains, by the first of them and how many there are; the set is one rule. */
    class Domains(val first: String, val count: Int) : RvRuleMatch

    /** The addresses a GeoIP database places in the country [code]. */
    class GeoIp(val code: String) : RvRuleMatch

    /** The private address ranges. */
    data object Lan : RvRuleMatch

    /** The last rule: whatever no rule above matched. */
    data object Rest : RvRuleMatch

    /** One value of a kind a rule can be written in: a full domain, a suffix, a keyword or an IP range. */
    class Value(val kind: RvMatchKind, val value: String) : RvRuleMatch

    /** A GeoSite category: the core has no GeoSite data yet, so such a rule is skipped and counted (D34). */
    class GeoSite(val category: String) : RvRuleMatch
}

/** The ways a rule can match, as the rule page offers them. */
internal enum class RvMatchKind(@StringRes val label: Int) {
    Full(R.string.rv_policy_match_full),
    Suffix(R.string.rv_policy_match_suffix),
    Keyword(R.string.rv_policy_match_keyword),
    Cidr(R.string.rv_policy_match_cidr),
    GeoIpCn(R.string.rv_policy_match_geoip_cn),
    Private(R.string.rv_policy_lan_kind),
}

/** Whether a rule routes: in use, turned off by the user, or skipped because the core cannot match it yet (D34). */
internal enum class RvRuleState { On, Off, Skipped }

/** A rule as the 策略 page lists it: what it matches, the group it sends to (null is 直連), and its bytes today. */
@Immutable
internal class RvPolicyRule(val match: RvRuleMatch, val group: RvPolicyGroup?, val bytes: Long, val state: RvRuleState = RvRuleState.On)

/**
 * The page's own state: the mode, the two switches, each selector's choice where the user changed it, and the order of
 * 全域's node list, kept here so the tab shows its last choice when the user comes back.
 */
@Immutable
internal data class RvPolicyState(
    val mode: RvRouteMode = RvRouteMode.Rule,
    val blockAds: Boolean = true,
    val blockQuic: Boolean = false,
    val choices: Map<String, String> = emptyMap(),
    val nodeSort: RvNodeSort = RvNodeSort.Latency,
) {
    fun group(group: RvPolicyGroup): RvPolicyGroup = choices[group.id]?.let(group::choosing) ?: group

    fun choose(groupId: String, nodeId: String) = copy(choices = choices + (groupId to nodeId))
}
