package dev.doona.android.feature.config

import dev.doona.android.prototype.rv.data.RV_NOW

/**
 * The configuration pages' sample tree, as the overview shows [rvSampleEngine]'s (`RvSampleConfig`): an entry with
 * three Linux-only settings, nodes, two rule files, DNS; generic names only.
 */
internal object RvConfigData {
    const val ENTRY = "config.dae"
    const val NODES = "nodes.dae"
    const val LAN = "rules/lan.dae"
    const val REV = 9

    /** Applied two hours before the sample's now, the same day. */
    const val APPLIED_AT = RV_NOW - 2 * 60 * 60 * 1000L

    val files = listOf(
        RvConfigFile(ENTRY, null, 36),
        RvConfigFile(NODES, ENTRY, 12),
        RvConfigFile("nodes/office.dae", NODES, 8),
        RvConfigFile("rules/cn.dae", ENTRY, 21),
        RvConfigFile(LAN, ENTRY, 9),
        RvConfigFile("dns.dae", ENTRY, 17),
    )

    val summary = RvConfigSummary(subscriptions = 2, nodes = 48, groups = 4, trafficRules = 23, dnsUpstreams = 3, dnsRequestRules = 4, dnsResponseRules = 1)

    /** The entry's `global` block asks for Linux's transparent proxy, which a VPN does not use. */
    val linuxOnly = listOf(
        RvSkipped("tproxy_port: 12345", ENTRY, 2, listOf(RvSkipReason.LinuxOnly)),
        RvSkipped("lan_interface: docker0", ENTRY, 3, listOf(RvSkipReason.LinuxOnly)),
        RvSkipped("wan_interface: auto", ENTRY, 4, listOf(RvSkipReason.LinuxOnly)),
    )

    /** The plan's skipped negative rule (config-plan.md:89-92), here in an include: both reasons on one record. */
    val lanRule = RvSkipped("!pname(helper) && mac(00:11:22:33:44:55) -> block", LAN, 3, listOf(RvSkipReason.ProcessName, RvSkipReason.MacAddress))

    /** The overview with the entry's Linux-only settings skipped. */
    val overview = RvConfigState(REV, APPLIED_AT, files, summary, linuxOnly, nowEpochMs = RV_NOW)

    /** The same with the negative rule in `rules/lan.dae` skipped too: the sample engine's head. */
    val skippedInclude = RvConfigState(REV, APPLIED_AT, files, summary, linuxOnly + lanRule, nowEpochMs = RV_NOW)
}
