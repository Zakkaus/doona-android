package dev.doona.android.feature.model

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import dev.doona.android.R

/**
 * A policy group: its id, its name as the profile gives it, how it picks, its node members, the groups among its members
 * ([groups]) and the member it uses now ([selectedId], a node or a group). honk lets a group list nodes and groups in any
 * mix, or nothing at all, so [selected] is the node its traffic goes through: the chosen node, the node the chosen group
 * ends on, or null when the choice leads to no node.
 */
@Immutable
internal class RvPolicyGroup(
    val id: String,
    val name: RvLabel,
    val kind: RvGroupKind,
    val members: List<RvNode>,
    val selectedId: String,
    val groups: List<RvMemberGroup> = emptyList(),
) {
    val selected: RvNode? = members.firstOrNull { it.id == selectedId } ?: groups.firstOrNull { it.id == selectedId }?.node

    /** The node members fastest first by their last test; a node whose last test timed out comes last. */
    val byLatency: List<RvNode> = members.sortedWith(compareBy(nullsLast()) { it.latencyMs })

    /** The node [memberId] stands for: a node member itself, or the node a member group ends on. */
    fun nodeOf(memberId: String): RvNode? = members.firstOrNull { it.id == memberId } ?: groups.firstOrNull { it.id == memberId }?.node

    fun choosing(memberId: String) = RvPolicyGroup(id, name, kind, members, memberId, groups)
}

/** A group listed as another group's member: its id, its name, how it picks, and the node its own choice ends on (null for none). */
@Immutable
internal data class RvMemberGroup(val id: String, val name: RvLabel, val kind: RvGroupKind, val node: RvNode?)

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

/**
 * Where a rule's flows end when it names no group of the profile: sent out directly (直連), refused (拒絕, honk's REJECT),
 * or sent to a group the profile does not have, which the page names as missing rather than as 直連.
 */
internal enum class RvRuleEnd { Direct, Reject, Missing }

/**
 * A rule as the 策略 page lists it: what it matches, the group it sends to, and its bytes today. With no [group], [end]
 * says where its flows end.
 */
@Immutable
internal class RvPolicyRule(
    val match: RvRuleMatch,
    val group: RvPolicyGroup?,
    val bytes: Long,
    val state: RvRuleState = RvRuleState.On,
    val end: RvRuleEnd = RvRuleEnd.Direct,
)

/**
 * The page's own state: the mode, the two switches, each selector's choice where the user changed it, and the order of
 * 全域's node list, kept here so the tab shows its last choice when the user comes back. [blockAds] is null where nothing
 * can block ads (the engine has no such option), and the page then has no 封鎖廣告 switch.
 */
@Immutable
internal data class RvPolicyState(
    val mode: RvRouteMode = RvRouteMode.Rule,
    val blockAds: Boolean? = true,
    val blockQuic: Boolean = false,
    val choices: Map<String, String> = emptyMap(),
    val nodeSort: RvNodeSort = RvNodeSort.Latency,
) {
    fun group(group: RvPolicyGroup): RvPolicyGroup = choices[group.id]?.let(group::choosing) ?: group

    fun choose(groupId: String, nodeId: String) = copy(choices = choices + (groupId to nodeId))
}

/** The id a rule targets to send its traffic out directly, as a group's id names the group. */
internal const val RV_DIRECT = "direct"
