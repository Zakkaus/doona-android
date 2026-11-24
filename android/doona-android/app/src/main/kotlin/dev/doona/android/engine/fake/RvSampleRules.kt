package dev.doona.android.engine.fake

import dev.doona.android.engine.RuleKind
import dev.doona.android.engine.RuleRow
import dev.doona.android.engine.RuleSkip
import dev.doona.android.engine.RuleTarget

/** The RV profile's rule list, in match order. */
internal object RvSampleRules {
    private val direct = RuleTarget.Direct
    private val proxy = RuleTarget.Group(FakeRuleMode.MAIN_GROUP_ID)
    private val streaming = RuleTarget.Group("g-Streaming")
    private val ai = RuleTarget.Group("g-AI")
    private val streamingLines = FakeRuleMode.LINES.filter { it.endsWith(",Streaming") }.take(12)
    private val aiLines = FakeRuleMode.LINES.filter { it.endsWith(",AI") }

    private val rules = listOf(
        sample("streaming", RuleKind.DomainSuffix, "netflix.com", streaming, streamingLines),
        sample("ai", RuleKind.DomainSuffix, "openai.com", ai, aiLines),
        sample("cn", RuleKind.GeoIp, "CN", direct, listOf("GEOIP,CN,DIRECT")),
        sample("lan", RuleKind.GeoIp, "private", direct, listOf("GEOIP,private,DIRECT")),
        sample("gemini", RuleKind.Domain, "gemini.google.com", ai, listOf("DOMAIN,gemini.google.com,AI")),
        sample("github", RuleKind.DomainSuffix, "github.com", proxy, listOf("DOMAIN-SUFFIX,github.com,Proxy")),
        sample("gamer", RuleKind.DomainKeyword, "gamer", RuleTarget.Group("g-TW"), listOf("DOMAIN-KEYWORD,gamer,TW")),
        sample("telegram", RuleKind.IpCidr, "91.108.56.0/22", proxy, listOf("IP-CIDR,91.108.56.0/22,Proxy")),
        geoSite("google", proxy),
        // The contract has no disabled state. Keep the row, but omit it from the effective config.
        sample("icloud", RuleKind.DomainSuffix, "icloud.com", direct, emptyList()),
        sample("steam", RuleKind.DomainSuffix, "steamcontent.com", direct, listOf("DOMAIN-SUFFIX,steamcontent.com,DIRECT")),
        geoSite("apple-cn", direct),
        sample("nintendo", RuleKind.DomainSuffix, "nintendo.net", RuleTarget.Group("g-JP"), listOf("DOMAIN-SUFFIX,nintendo.net,JP")),
        sample("match", RuleKind.Match, null, proxy, listOf("MATCH,Proxy")),
    )
    val rows = rules.mapIndexed { index, (row) -> row.copy(index = index) }
    val configLines = rules.flatMap { (_, lines) -> lines }
    val source = FakeRuleMode.SOURCE.copy(
        rules = rows.size,
        lines = rows.sumOf { it.count.coerceAtLeast(1) },
        skipped = rows.sumOf { it.skippedLines },
        rulesRevision = 1L,
    )

    private fun sample(id: String, kind: RuleKind, value: String?, target: RuleTarget, lines: List<String>): Sample =
        Sample(RuleRow("r-$id", 0, kind, kind.name, value, if (value == null) 0 else lines.size.coerceAtLeast(1), target), lines)

    private fun geoSite(category: String, target: RuleTarget) = Sample(
        RuleRow("r-$category", 0, null, "GEOSITE", category, 1, target, RuleSkip.GeodataMissing, skippedLines = 1),
        listOf("# skipped GEOSITE,$category: geodata_missing"),
    )

    private data class Sample(val row: RuleRow, val configLines: List<String>)
}
