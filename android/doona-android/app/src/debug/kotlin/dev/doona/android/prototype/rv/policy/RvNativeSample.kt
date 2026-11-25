package dev.doona.android.prototype.rv.policy

import dev.doona.android.designsystem.rv.RvPlace
import dev.doona.android.feature.model.RvDnsServer
import dev.doona.android.feature.model.RvGroupKind
import dev.doona.android.feature.model.RvLabel
import dev.doona.android.feature.model.RvMatchKind
import dev.doona.android.feature.model.RvNode
import dev.doona.android.feature.model.RvPolicyGroup
import dev.doona.android.feature.model.RvPolicyRule
import dev.doona.android.feature.model.RvRuleEnd
import dev.doona.android.feature.model.RvRuleMatch
import dev.doona.android.feature.model.RvRuleState
import dev.doona.android.prototype.rv.data.RvData

/**
 * A hand-written honk config as the native pages show it (brief p14, schema 4 §1): no group called Proxy, every policy,
 * nesting three deep (海外 › 日本 › 東京), `direct` as a literal member, Chinese group names. The rules send to those
 * groups, to 直連 and to 拒絕; one is paused; the last routes the rest to 海外. Its DNS has three servers, four query rules
 * (the ad block paused) and one response rule.
 */
internal object RvNativeSample {
    private val nodes = RvData.nodes
    private fun List<RvNode>.inPlace(place: RvPlace) = filter { it.place == place }
    private fun group(id: String, name: String, kind: RvGroupKind, place: RvPlace): RvPolicyGroup {
        val members = nodes.inPlace(place)
        return RvPolicyGroup(id, RvLabel.Text(name), kind, members, members.first().id)
    }

    private val overseasGroup = group("g-海外", OVERSEAS, RvGroupKind.Fallback, RvPlace.SG)
    private val japanGroup = group("g-日本", JAPAN, RvGroupKind.Auto, RvPlace.JP)
    private val usGroup = group("g-美國", US, RvGroupKind.Auto, RvPlace.US)
    private val streamingGroup = group("g-影音", STREAMING, RvGroupKind.Score, RvPlace.HK)
    private val tokyoGroup = group("g-東京", TOKYO, RvGroupKind.Select, RvPlace.JP)

    /** Every group of the config a rule can send to, in file order: 海外, 日本 and the 東京 it nests, 美國, 影音. */
    val targets: List<RvPolicyGroup> = listOf(overseasGroup, japanGroup, tokyoGroup, usGroup, streamingGroup)

    /** 海外, a Fallback group: two nodes, the nested 日本 (which nests 東京) and 美國, then `direct` last. */
    val overseas = RvGroupDraft(
        name = OVERSEAS,
        policy = RvHonkPolicy.Fallback,
        members = listOf(
            RvMemberRef.Node("n-sg1", "Singapore 01", "sg"),
            RvMemberRef.Node("n-hk2", "Hong Kong 02 IPLC", "hk"),
            RvMemberRef.Group("g-日本", JAPAN, RvHonkPolicy.UrlTest),
            RvMemberRef.Group("g-美國", US, RvHonkPolicy.LoadBalance),
            RvMemberRef.Direct,
        ),
        defaultId = "g-日本",
        finalId = RvMemberRef.Direct.id,
    )

    /** The rules in file order, the last one routing whatever no rule above matched. */
    val rules: List<RvPolicyRule> = listOf(
        RvPolicyRule(RvRuleMatch.Value(RvMatchKind.Suffix, "github.com"), overseasGroup, 812_400_000L),
        RvPolicyRule(RvRuleMatch.GeoSite("netflix"), streamingGroup, 1_204_000_000L),
        RvPolicyRule(RvRuleMatch.Value(RvMatchKind.Keyword, "openai"), usGroup, 182_300_000L),
        RvPolicyRule(RvRuleMatch.Value(RvMatchKind.Full, "chat.example"), japanGroup, 0L, RvRuleState.Off),
        RvPolicyRule(RvRuleMatch.GeoIp("CN"), null, 728_100_000L),
        RvPolicyRule(RvRuleMatch.Lan, null, 41_206_118L),
        RvPolicyRule(RvRuleMatch.Value(RvMatchKind.Suffix, "ads.example"), null, 0L, end = RvRuleEnd.Reject),
        RvPolicyRule(RvRuleMatch.Rest, overseasGroup, 263_000_000L),
    )

    /** The list after the user removed every rule they wrote: only the last rule, which routes everything, is left. */
    val restOnly: List<RvPolicyRule> = rules.takeLast(1)

    /** The config's DNS servers, as dns.dae declares them in `dns.upstream` (the sample engine's `RvSampleConfig`). */
    val upstreams: List<RvDnsServer> = listOf(
        RvDnsServer("local", "udp://223.5.5.5:53"),
        RvDnsServer("remote", "tcp+udp://1.1.1.1:53"),
        RvDnsServer("backup", "https://dns.example/dns-query"),
    )

    /** dns.dae's `qname(suffix: corp.example) -> local`, a query rule the form can represent. A DNS draft has no target. */
    val dnsQuery = RvDnsRuleDraft(RvDnsList.Query, RvRuleDraft(RvMatchKind.Suffix, "corp.example", ""), RvDnsAction.Upstream, "local")

    /** `qname(suffix: lan) -> asis`, the last query rule before the fallback. */
    val dnsAsIs = RvDnsRuleDraft(RvDnsList.Query, RvRuleDraft(RvMatchKind.Suffix, "lan", ""), RvDnsAction.AsIs)

    /** The ad block, `qname(geosite: category-ads-all) -> reject`, paused (commented out, schema 4 §7 `blockAds: off`). */
    val dnsAds = RvDnsRuleDraft(RvDnsList.Query, RvRuleDraft(RvMatchKind.Suffix, "", "", enabled = false), RvDnsAction.Reject)

    /** The only response rule, `upstream(remote) && ip(geoip: private) -> backup`: a private answer is asked again. */
    val dnsPrivate = RvDnsRuleDraft(RvDnsList.Response, RvRuleDraft(RvMatchKind.Private, "", ""), RvDnsAction.Upstream, "backup")

    const val OVERSEAS = "海外"
    const val JAPAN = "日本"
    const val US = "美國"
    const val STREAMING = "影音"
    const val TOKYO = "東京"
}
