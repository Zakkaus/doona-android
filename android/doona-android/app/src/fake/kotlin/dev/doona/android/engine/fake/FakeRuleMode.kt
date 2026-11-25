package dev.doona.android.engine.fake

import dev.doona.android.engine.DayTotal
import dev.doona.android.engine.DayUsage
import dev.doona.android.engine.EffectiveConfig
import dev.doona.android.engine.Group
import dev.doona.android.engine.Member
import dev.doona.android.engine.Mode
import dev.doona.android.engine.NodeCodes
import dev.doona.android.engine.OutboundBytes
import dev.doona.android.engine.RuleBytes
import dev.doona.android.engine.RuleKind
import dev.doona.android.engine.RuleOptions
import dev.doona.android.engine.RuleRow
import dev.doona.android.engine.RuleSource
import dev.doona.android.engine.RuleTarget
import dev.doona.android.engine.RuleTemplate
import dev.doona.android.engine.Usage

/**
 * Rule mode as the core serves its template `cn-direct` version 2, taken from the payloads in `contracts/doona-core/`:
 * the rows and their ids, the source, the effective config's rule lines and the usage day. Today's proxied bytes are
 * spread over the 串流, AI and final rows, so each rule of the 策略 page has traffic; the day's totals stay the fixture's.
 */
object FakeRuleMode {
    const val TEMPLATE = "cn-direct"

    /** doona web's five routing modes, as the approved 範本 page lists them; the installed data lacks GFW's GeoSite list. */
    val TEMPLATES = listOf(
        RuleTemplate(TEMPLATE),
        RuleTemplate("services"),
        RuleTemplate("regions"),
        RuleTemplate("homebound"),
        RuleTemplate("gfw", needsGeosite = true),
    )
    const val MAIN_GROUP_ID = "g-Proxy"
    private const val STREAMING = "g-Streaming"
    private const val AI = "g-AI"

    private const val PRIVATE_ROW = "r-bcd21d2c53a25096"
    private const val STREAMING_ROW = "r-a5a7859d51595d08"
    private const val AI_ROW = "r-20f4fbe9f4a75cef"
    private const val CN_ROW = "r-8d066c6f0b6552cd"
    private const val MATCH_ROW = "r-6e59b550be7e592b"

    val SOURCE = RuleSource(
        template = TEMPLATE,
        version = 2,
        subscriptionId = null,
        options = RuleOptions(blockQuic = false),
        rules = 5,
        lines = 23,
        skipped = 0,
        rulesRevision = 91044300313602L,
    )

    val ROWS: List<RuleRow> = listOf(
        row(PRIVATE_ROW, 0, RuleKind.GeoIp, "private", 1, RuleTarget.Direct),
        row(STREAMING_ROW, 1, RuleKind.DomainSuffix, "netflix.com", 14, RuleTarget.Group(STREAMING)),
        row(AI_ROW, 2, RuleKind.DomainSuffix, "openai.com", 6, RuleTarget.Group(AI)),
        row(CN_ROW, 3, RuleKind.GeoIp, "CN", 1, RuleTarget.Direct),
        row(MATCH_ROW, 4, RuleKind.Match, null, 0, RuleTarget.Group(MAIN_GROUP_ID)),
    )

    /** The compiled rules in the effective config's text, one per value. */
    internal val LINES = listOf("GEOIP,private,DIRECT") +
        listOf(
            "netflix.com", "netflix.net", "nflxext.com", "nflximg.net", "nflxso.net", "nflxvideo.net", "youtube.com",
            "googlevideo.com", "ytimg.com", "disneyplus.com", "bamgrid.com", "dssott.com", "max.com", "primevideo.com",
        ).map { "DOMAIN-SUFFIX,$it,Streaming" } +
        listOf("openai.com", "chatgpt.com", "oaistatic.com", "oaiusercontent.com", "anthropic.com", "claude.ai").map { "DOMAIN-SUFFIX,$it,AI" } +
        listOf("GEOIP,CN,DIRECT", "MATCH,Proxy")

    internal val DNS_UPSTREAMS = listOf("1.1.1.1:53", "8.8.8.8:53")

    val USAGE = Usage(
        today = DayUsage(
            date = "2026-11-22",
            up = hours(0 to 9_418_230, 1 to 86_302_117, 7 to 7_150_938, 8 to 17_236_965, 9 to 70_886_402, 10 to 42_088_207),
            down = hours(0 to 142_318_604, 1 to 38_207_551, 7 to 96_441_027, 8 to 273_501_481, 9 to 979_552_804, 10 to 648_039_033),
            flows = hours(0 to 96, 1 to 31, 7 to 118, 8 to 142, 9 to 203, 10 to 84),
            outbounds = listOf(
                OutboundBytes("direct", 22_050_112, 401_220_310),
                OutboundBytes(AI, 48_918_377, 96_402_005),
                OutboundBytes(MAIN_GROUP_ID, 120_908_512, 540_470_108),
                OutboundBytes(STREAMING, 31_204_118, 1_102_337_920),
            ),
            byRule = listOf(
                RuleBytes(AI_ROW, 48_918_377, 96_402_005, 51),
                RuleBytes(MATCH_ROW, 120_908_512, 540_470_108, 161),
                RuleBytes(CN_ROW, 22_050_112, 401_220_310, 398),
                RuleBytes(STREAMING_ROW, 31_204_118, 1_102_337_920, 64),
            ),
        ),
        yesterday = DayUsage(
            date = "2026-11-21",
            up = hours(0 to 5_101_223, 8 to 20_113_002, 9 to 96_110_311, 10 to 41_220_178, 11 to 12_009_871, 19 to 30_110_092, 20 to 4_100_223),
            down = hours(
                0 to 88_112_043,
                8 to 412_337_019,
                9 to 1_203_381_774,
                10 to 690_001_223,
                11 to 301_112_876,
                19 to 512_009_331,
                20 to 96_001_200,
            ),
            flows = hours(0 to 40, 8 to 152, 9 to 240, 10 to 180, 11 to 77, 19 to 131, 20 to 22),
            outbounds = listOf(OutboundBytes(MAIN_GROUP_ID, 207_764_900, 3_282_955_466)),
        ),
        days = listOf(DayTotal("2026-11-19", 98_112_004, 2_210_031_998), DayTotal("2026-11-21", 208_764_900, 3_302_955_466)),
    )

    /**
     * The effective config the core would give for [mode], [groups] and [source]: the same header and group comments,
     * each node by its code only, then the template's rule lines.
     */
    fun effectiveConfig(mode: Mode, groups: List<Group>, source: RuleSource, lines: List<String> = LINES, upstreams: List<String> = DNS_UPSTREAMS): EffectiveConfig {
        fun name(id: String) = groups.firstOrNull { it.id == id }?.name ?: id.removePrefix("g-")
        fun member(m: Member) = if (m.isGroup) m.name else NodeCodes.codeOf(m.id)
        val direct = mode == Mode.Direct
        val text = buildString {
            appendLine("# mode: $mode")
            appendLine(
                "# rules: template ${source.template ?: "none"} version ${source.version ?: 0}, blockQuic ${source.options.blockQuic}, " +
                    "${source.rules} rows, ${source.lines} lines, ${source.skipped} skipped",
            )
            appendLine("# main group: ${name(MAIN_GROUP_ID)}")
            appendLine("# dns: via ${if (direct) "DIRECT" else name(MAIN_GROUP_ID)}, upstreams ${upstreams.joinToString(", ")}")
            groups.forEach { g ->
                val now = g.members.firstOrNull { it.id == g.selectedId }?.let(::member) ?: "none"
                appendLine("# group ${g.name} (${g.kind}, now $now): ${g.members.joinToString(", ", transform = ::member)}")
            }
            lines.forEach(::appendLine)
        }
        val via = if (direct) RuleTarget.Direct else RuleTarget.Group(MAIN_GROUP_ID)
        return EffectiveConfig(mode, MAIN_GROUP_ID, via, upstreams, source, text, truncated = false)
    }

    private fun row(id: String, index: Int, kind: RuleKind, sample: String?, count: Int, target: RuleTarget) =
        RuleRow(id, index, kind, kind.name, sample, count, target)

    /** A day's 24 hourly values: zero but in the hours given. */
    private fun hours(vararg values: Pair<Int, Number>): List<Long> =
        LongArray(HOURS).also { day -> values.forEach { (hour, value) -> day[hour] = value.toLong() } }.toList()

    private const val HOURS = 24
}
