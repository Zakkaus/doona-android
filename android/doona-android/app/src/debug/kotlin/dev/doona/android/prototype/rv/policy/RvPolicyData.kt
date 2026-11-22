package dev.doona.android.prototype.rv.policy

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import dev.doona.android.R
import dev.doona.android.prototype.rv.data.RvData
import dev.doona.android.prototype.rv.data.RvNode
import dev.doona.android.prototype.rv.data.RvPlace
import dev.doona.android.prototype.rv.data.RvRouteMode
import dev.doona.android.prototype.rv.data.fastest

/** How a group picks its node: the user picks it (a selector), or the fastest by the last test does (url-test). */
internal enum class RvGroupKind(@StringRes val label: Int, @DrawableRes val glyph: Int) {
    Select(R.string.rv_policy_kind_select, R.drawable.rv_sym_touch_app),
    Auto(R.string.rv_policy_kind_auto, R.drawable.rv_sym_bolt),
}

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

/** A connection the quick rule starts from: where it went, from which app, the rule it matched and the group it used. */
@Immutable
internal class RvConnection(val host: String, val port: Int, val app: String, val rule: RvPolicyRule)

/**
 * The 策略 page's routing, built from [RvData] so that it agrees with 活動's outbound card: the main node is the node in
 * use, 串流 and AI choose the nodes `RvData.ruleOutbounds` names, the rules' bytes are those outbounds' bytes, and the
 * profile has `RvData.RULES` rules. The card shows five of them; together they carry all of today's bytes.
 */
internal object RvPolicyData {
    private val nodes = RvData.nodes
    private val streamingOut = RvData.ruleOutbounds.first { it.group == R.string.rv_rule_streaming }
    private val aiOut = RvData.ruleOutbounds.first { it.group == R.string.rv_rule_ai }

    /** The final group: whatever no rule sends elsewhere goes through its choice, the node in use. */
    val main = RvPolicyGroup(MAIN, R.string.rv_main_node, RvGroupKind.Select, nodes, RvData.CURRENT_ID)
    val auto = fastest(AUTO, R.string.rv_policy_group_auto, nodes)
    val streaming = RvPolicyGroup(STREAMING, R.string.rv_rule_streaming, RvGroupKind.Select, nodes.inPlaces(STREAMING_PLACES), streamingOut.nodeId!!)
    val ai = RvPolicyGroup(AI, R.string.rv_rule_ai, RvGroupKind.Select, nodes.inPlaces(AI_PLACES), aiOut.nodeId!!)
    private val regions = REGIONS.map { fastest(it.name.lowercase(), it.label, nodes.inPlaces(setOf(it))) }

    /** The 策略組 card's groups in the profile's order; the main group has its own card. */
    val groups: List<RvPolicyGroup> = listOf(auto, streaming, ai) + regions

    fun group(id: String): RvPolicyGroup = (listOf(main) + groups).first { it.id == id }

    /** The five rules the card lists, in the profile's order: the first four, then the last one. */
    val rules: List<RvPolicyRule> = listOf(
        RvPolicyRule(RvRuleMatch.Domains(STREAMING_FIRST, STREAMING_DOMAINS), streaming, streamingOut.bytes),
        RvPolicyRule(RvRuleMatch.Domains(AI_FIRST, AI_DOMAINS), ai, aiOut.bytes),
        RvPolicyRule(RvRuleMatch.GeoIp(CN), null, RvData.split.directBytes - LAN_BYTES),
        RvPolicyRule(RvRuleMatch.Lan, null, LAN_BYTES),
        RvPolicyRule(RvRuleMatch.Rest, main, RvData.split.proxyBytes - streamingOut.bytes - aiOut.bytes),
    )

    const val RULES = RvData.RULES

    /**
     * All [RULES] rules in the profile's order: the card's first four; nine the user added before the last rule, where
     * the quick rule puts them, among them one turned off and two GeoSite rules the core skips; then the last rule. The
     * card's five carry all of today's bytes, so the nine carried none.
     */
    val allRules: List<RvPolicyRule> = rules.dropLast(1) + listOf(
        RvPolicyRule(RvRuleMatch.Value(RvMatchKind.Full, SAMPLE_HOST), ai, 0),
        RvPolicyRule(RvRuleMatch.Value(RvMatchKind.Suffix, "github.com"), main, 0),
        RvPolicyRule(RvRuleMatch.Value(RvMatchKind.Keyword, "gamer"), group("tw"), 0),
        RvPolicyRule(RvRuleMatch.Value(RvMatchKind.Cidr, "91.108.56.0/22"), main, 0),
        RvPolicyRule(RvRuleMatch.GeoSite("google"), main, 0, RvRuleState.Skipped),
        RvPolicyRule(RvRuleMatch.Value(RvMatchKind.Suffix, "icloud.com"), null, 0, RvRuleState.Off),
        RvPolicyRule(RvRuleMatch.Value(RvMatchKind.Suffix, "steamcontent.com"), null, 0),
        RvPolicyRule(RvRuleMatch.GeoSite("apple-cn"), null, 0, RvRuleState.Skipped),
        RvPolicyRule(RvRuleMatch.Value(RvMatchKind.Suffix, "nintendo.net"), group("jp"), 0),
    ) + rules.last()

    /** Two quick rules held for a later 套用, as doona web holds them: the page shows them above the list. */
    val pending: List<RvPolicyRule> = listOf(
        RvPolicyRule(RvRuleMatch.Value(RvMatchKind.Suffix, "claude.ai"), ai, 0),
        RvPolicyRule(RvRuleMatch.Value(RvMatchKind.Full, "ani.gamer.com.tw"), group("tw"), 0),
    )

    /** The quick rule's sample: a connection the last rule sent through the main node. */
    val connection = RvConnection(SAMPLE_HOST, HTTPS, SAMPLE_APP, rules.last())

    private fun fastest(id: String, @StringRes name: Int, members: List<RvNode>) = RvPolicyGroup(id, name, RvGroupKind.Auto, members, members.fastest(1).first().id)

    private fun List<RvNode>.inPlaces(places: Set<RvPlace>) = filter { it.place in places }
}

/** The page's own state: the mode, the two switches, and each selector's choice where the user changed it. */
@Immutable
internal data class RvPolicyState(
    val mode: RvRouteMode = RvRouteMode.Rule,
    val blockAds: Boolean = true,
    val blockQuic: Boolean = false,
    val choices: Map<String, String> = emptyMap(),
) {
    fun group(group: RvPolicyGroup): RvPolicyGroup = choices[group.id]?.let(group::choosing) ?: group

    fun choose(groupId: String, nodeId: String) = copy(choices = choices + (groupId to nodeId))
}

/** What the quick rule matches on, and the part of the host each choice keeps. */
internal enum class RvMatchBy(@StringRes val label: Int, @StringRes val line: Int) {
    Full(R.string.rv_policy_match_full, R.string.rv_policy_match_full_line),
    Suffix(R.string.rv_policy_match_suffix, R.string.rv_policy_match_suffix_line),
    Keyword(R.string.rv_policy_match_keyword, R.string.rv_policy_match_keyword_line),
    ;

    fun of(host: String): String = when (this) {
        Full -> host
        Suffix -> host.split('.').takeLast(2).joinToString(".")
        Keyword -> host.substringBefore('.')
    }
}

/** The quick rule being written: how it matches and where it sends; [targetId] is a group's id or [RV_DIRECT]. */
@Immutable
internal data class RvQuickRule(val matchBy: RvMatchBy = RvMatchBy.Full, val targetId: String = AI)

internal const val RV_DIRECT = "direct"

private const val MAIN = "main"
private const val AUTO = "auto"
private const val STREAMING = "streaming"
private const val AI = "ai"

private val STREAMING_PLACES = setOf(RvPlace.HK, RvPlace.TW, RvPlace.JP, RvPlace.SG, RvPlace.US)
private val AI_PLACES = setOf(RvPlace.TW, RvPlace.JP, RvPlace.SG, RvPlace.US)
private val REGIONS = listOf(RvPlace.HK, RvPlace.TW, RvPlace.JP, RvPlace.US)

private const val STREAMING_FIRST = "netflix.com"
private const val STREAMING_DOMAINS = 12
private const val AI_FIRST = "openai.com"
private const val AI_DOMAINS = 6
private const val CN = "CN"

/** Of the bytes that went direct today, what the private ranges carried (a NAS and the router); GeoIP CN took the rest. */
private const val LAN_BYTES = 41_206_118L

private const val SAMPLE_HOST = "gemini.google.com"
private const val SAMPLE_APP = "Chrome"
private const val HTTPS = 443
