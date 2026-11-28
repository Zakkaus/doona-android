package dev.doona.android.prototype.rv.policy

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RvPlace
import dev.doona.android.feature.model.RV_DIRECT
import dev.doona.android.feature.model.RvGroupKind
import dev.doona.android.feature.model.RvLabel
import dev.doona.android.feature.model.RvMatchKind
import dev.doona.android.feature.model.RvNode
import dev.doona.android.feature.model.RvPolicyGroup
import dev.doona.android.feature.model.RvPolicyRule
import dev.doona.android.feature.model.RvRuleMatch
import dev.doona.android.feature.model.RvRuleState
import dev.doona.android.feature.model.fastest
import dev.doona.android.prototype.rv.data.RV_NOW
import dev.doona.android.prototype.rv.data.RvData

/**
 * A connection: where it went, from which app, the rule it matched (whose group it used), and, for the 連線 list, the
 * app's package, TCP or UDP, its bytes each way and when it started. The quick rule starts from one.
 */
@Immutable
internal class RvConnection(
    val host: String,
    val port: Int,
    val app: String,
    val rule: RvPolicyRule,
    val packageName: String = "",
    val udp: Boolean = false,
    val downBytes: Long = 0,
    val upBytes: Long = 0,
    val startedAtEpochMs: Long = RV_NOW,
) {
    /** The group that carried it: the rule's, or none when the rule sends it direct. */
    val group: RvPolicyGroup? get() = rule.group

    /** The node that carried it, or none for 直連. */
    val node get() = rule.group?.selected

    /** Whether the core knew a name for it (from its DNS answers); without one it is matched by address only. */
    val hasDomain: Boolean get() = host.any { it.isLetter() }
}

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
    val main = RvPolicyGroup(MAIN, RvLabel.Res(R.string.rv_main_node), RvGroupKind.Select, nodes, RvData.CURRENT_ID)
    val auto = fastest(AUTO, R.string.rv_policy_group_auto, nodes)
    val streaming = RvPolicyGroup(STREAMING, RvLabel.Res(R.string.rv_rule_streaming), RvGroupKind.Select, nodes.inPlaces(STREAMING_PLACES), streamingOut.nodeId!!)
    val ai = RvPolicyGroup(AI, RvLabel.Res(R.string.rv_rule_ai), RvGroupKind.Select, nodes.inPlaces(AI_PLACES), aiOut.nodeId!!)
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

    /**
     * The connections open now (10:24), newest first: each matched one of [allRules], so its group and node agree with
     * the rules list; Telegram's has no name, so only its address rule could match it.
     */
    val connections: List<RvConnection> = run {
        val r = allRules
        listOf(
            RvConnection(SAMPLE_HOST, HTTPS, SAMPLE_APP, r[4], "com.android.chrome", downBytes = 1_842_310, upBytes = 214_906, startedAtEpochMs = RV_NOW - 48_000),
            RvConnection("chatgpt.com", HTTPS, "ChatGPT", r[1], "com.openai.chatgpt", downBytes = 3_402_118, upBytes = 618_440, startedAtEpochMs = RV_NOW - 140_000),
            RvConnection("szshort.weixin.qq.com", HTTPS, "WeChat", r[2], "com.tencent.mm", downBytes = 482_903, upBytes = 121_377, startedAtEpochMs = RV_NOW - 295_000),
            RvConnection("rr3---sn-a5mekn6d.googlevideo.com", HTTPS, "YouTube", r[0], "com.google.android.youtube", udp = true, downBytes = 412_093_551, upBytes = 3_104_228, startedAtEpochMs = RV_NOW - 1_050_000),
            RvConnection("91.108.56.130", HTTPS, "Telegram", r[7], "org.telegram.messenger", downBytes = 12_630_092, upBytes = 2_318_406, startedAtEpochMs = RV_NOW - 1_558_000),
            RvConnection("occ-0-4415-58.1.nflxvideo.net", HTTPS, "Netflix", r[0], "com.netflix.mediaclient", downBytes = 286_211_870, upBytes = 1_902_334, startedAtEpochMs = RV_NOW - 2_440_000),
            RvConnection("imap.gmail.com", IMAPS, "Gmail", r.last(), "com.google.android.gm", downBytes = 6_104_772, upBytes = 402_118, startedAtEpochMs = RV_NOW - 4_276_000),
        )
    }

    /** The quick rule's sample: a connection the last rule sent through the main node. */
    val connection = RvConnection(SAMPLE_HOST, HTTPS, SAMPLE_APP, rules.last())

    private fun fastest(id: String, @StringRes name: Int, members: List<RvNode>) = RvPolicyGroup(id, RvLabel.Res(name), RvGroupKind.Auto, members, members.fastest(1).first().id)

    private fun List<RvNode>.inPlaces(places: Set<RvPlace>) = filter { it.place in places }
}

/** What the quick rule matches on, and the part of the host each choice keeps. */
internal enum class RvMatchBy(@StringRes val label: Int, @StringRes val line: Int) {
    Full(R.string.rv_policy_match_full, R.string.rv_policy_match_full_line),
    Suffix(R.string.rv_policy_match_suffix, R.string.rv_rule_raw_suffix_line),
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
private const val IMAPS = 993
