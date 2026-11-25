package dev.doona.android.engine.fake

import dev.doona.android.engine.ConfigFile
import dev.doona.android.engine.ConfigRevision
import dev.doona.android.engine.ConfigSummary
import dev.doona.android.engine.RevisionOrigin
import dev.doona.android.engine.SupportReason

/**
 * The sample honk tree of the configuration pages: an entry with three Linux-only settings and four groups, nodes with
 * an include of their own, two rule files (the plan's negative rule in `rules/lan.dae`, config-plan.md:89–92) and DNS.
 * Revision 9 is the head; 8 and 7 send `ai` through the US nodes, 9 through Japan's. Generic names only.
 */
internal object RvSampleConfig {
    /** 2026-11-22 10:24:00 UTC, the samples' now. */
    const val NOW = 1_795_343_040_000L
    private const val HOUR = 60 * 60 * 1000L
    private const val DAY = 24 * HOUR
    const val HEAD = 9

    const val ENTRY = "f1"
    const val NODES = "f2"
    const val OFFICE = "f3"
    const val CN = "f4"
    const val LAN = "f5"
    const val DNS = "f6"

    private fun entry(aiKeyword: String) = """
        global {
          tproxy_port: 12345
          lan_interface: docker0
          wan_interface: auto
          log_level: info
          tcp_check_url: 'http://cp.cloudflare.com'
          check_interval: 30s
        }

        include {
          nodes.dae
          rules/cn.dae
          rules/lan.dae
          dns.dae
        }

        group {
          proxy {
            filter: subtag(feed)
            policy: min_moving_avg
          }

          # 影音
          media {
            filter: name(keyword: 'HK')
            policy: min
          }
          ai {
            filter: name(keyword: '$aiKeyword')
            policy: min_moving_avg
          }
          office {
            filter: subtag(office)
            policy: fixed(0)
          }
        }
    """.trimIndent()

    private val nodes = """
        # 節點
        include {
          nodes/office.dae
        }

        subscription {
          feed: 'https://sub.example/a'
          backup: 'https://sub.example/b'
        }

        node {
          lab: 'socks5://10.0.0.2:1080'
        }
    """.trimIndent()

    private val office = """
        # 公司節點
        node {
          office-hk: 'trojan://office.example:443'
          office-jp: 'trojan://office.example:8443'
          office-sg: 'trojan://office.example:9443'
        }

        # 由 IT 維護
    """.trimIndent()

    /** `rules/cn.dae`; the draft of the source editor adds the wiki line and sends 影音 to `media`. */
    fun cn(target: String, wiki: Boolean) = listOfNotNull(
        "# 中國大陸直連",
        "routing {",
        "  domain(geosite:cn) -> direct",
        "  dip(geoip:cn) -> direct",
        "  domain(suffix: cn.example) -> direct",
        "",
        "  domain(keyword: 影音) -> $target",
        "  domain(suffix: video.example) -> media",
        "  domain(suffix: music.example) -> media",
        "",
        "  # 公司內網",
        "  domain(suffix: corp.example) -> direct",
        "  domain(suffix: wiki.corp.example) -> direct".takeIf { wiki },
        "  dip(10.8.0.0/16) -> direct",
        "",
        "  # 下載",
        "  domain(suffix: mirror.example) -> direct",
        "  domain(suffix: cdn.example) && l4proto(tcp) -> direct",
        "",
        "  domain(suffix: chat.example) -> proxy",
        "  domain(suffix: news.example) -> proxy",
        "}",
    ).joinToString("\n")

    const val LAN_RULE = "!pname(helper) && mac(00:11:22:33:44:55) -> block"

    private val lan = """
        routing {
          dip(192.168.0.0/16) -> direct
          $LAN_RULE
          dip(172.16.0.0/12) -> direct

          # 印表機
          dip(192.168.1.20) -> direct
          fallback: proxy
        }
    """.trimIndent()

    private val dns = """
        dns {
          upstream {
            local: 'udp://223.5.5.5:53'
            remote: 'tcp+udp://1.1.1.1:53'
            backup: 'https://dns.example/dns-query'
          }
          routing {
            request {
              qname(geosite: category-ads-all) -> reject
              qname(geosite:cn) -> local
              qname(suffix: corp.example) -> local
              qname(suffix: lan) -> asis
              fallback: remote
            }
            response {
              upstream(remote) && ip(geoip: private) -> backup
            }
          }
        }
    """.trimIndent()

    /** The head's files in load order: the entry, each include under the file that names it. */
    private fun paths() = listOf(
        ENTRY to ("config.dae" to null),
        NODES to ("nodes.dae" to ENTRY),
        OFFICE to ("nodes/office.dae" to NODES),
        CN to ("rules/cn.dae" to ENTRY),
        LAN to ("rules/lan.dae" to ENTRY),
        DNS to ("dns.dae" to ENTRY),
    )

    /** The texts of a revision of the tree: [aiKeyword] the `ai` group's, [cnTarget] where 影音 goes. */
    fun texts(aiKeyword: String = "JP", cnTarget: String = "proxy", wiki: Boolean = false): Map<String, String> = mapOf(
        ENTRY to entry(aiKeyword),
        NODES to nodes,
        OFFICE to office,
        CN to cn(cnTarget, wiki),
        LAN to lan,
        DNS to dns,
    )

    fun files(texts: Map<String, String>): List<ConfigFile> =
        paths().map { (id, at) -> ConfigFile(id, at.first, at.second, texts.getValue(id).lines().size) }

    val summary = ConfigSummary(subscriptions = 2, nodes = 48, groups = 4, trafficRules = 23, dnsUpstreams = 3, dnsRequestRules = 4, dnsResponseRules = 1)

    /** The support report's records: the entry's Linux-only settings, then the rule in `rules/lan.dae`. */
    val skipped = listOf(
        Triple(ENTRY, "tproxy_port: 12345", listOf(SupportReason.LinuxOnly)),
        Triple(ENTRY, "lan_interface: docker0", listOf(SupportReason.LinuxOnly)),
        Triple(ENTRY, "wan_interface: auto", listOf(SupportReason.LinuxOnly)),
        Triple(LAN, LAN_RULE, listOf(SupportReason.ProcessName, SupportReason.MacAddress)),
    )

    /** The groups, built-ins, DNS upstreams and DNS actions a rule may name; the fake's check refuses any other after `->`. */
    val targets = setOf("direct", "block", "proxy", "media", "ai", "office", "local", "remote", "backup", "reject", "asis")

    /** The applied revisions, newest first; the app keeps the latest ten, and there are nine. */
    val history = listOf(
        ConfigRevision(9, NOW - 2 * HOUR, RevisionOrigin.Edit),
        ConfigRevision(8, NOW - DAY - 13 * HOUR, RevisionOrigin.Restore, restored = 6),
        ConfigRevision(7, NOW - DAY - 15 * HOUR, RevisionOrigin.Edit),
        ConfigRevision(6, NOW - 3 * DAY - 2 * HOUR, RevisionOrigin.Import),
        ConfigRevision(5, NOW - 5 * DAY - 4 * HOUR, RevisionOrigin.Edit),
        ConfigRevision(4, NOW - 6 * DAY - 1 * HOUR, RevisionOrigin.Edit),
        ConfigRevision(3, NOW - 9 * DAY - 7 * HOUR, RevisionOrigin.Import),
        ConfigRevision(2, NOW - 12 * DAY - 3 * HOUR, RevisionOrigin.Edit),
        ConfigRevision(1, NOW - 12 * DAY - 5 * HOUR, RevisionOrigin.Template),
    )

    /** Every revision's texts: 9 sends `ai` through Japan, the older ones through the US. */
    fun revisions(): Map<Int, Map<String, String>> = history.associate { it.rev to if (it.rev == HEAD) texts() else texts(aiKeyword = "US") }

    /** The staged imports the fake knows: one past the 32-file budget, one whose entry includes a path outside its root. */
    const val IMPORT_LIMIT = "import-limit"
    const val IMPORT_ESCAPE = "import-escape"
    const val ESCAPE_PATH = "/etc/honk/rules.d/*.dae"
}
