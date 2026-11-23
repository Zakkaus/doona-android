package dev.doona.android.prototype.rv.config

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import dev.doona.android.R
import dev.doona.android.prototype.rv.data.RV_NOW

/**
 * A file of the configuration tree, as `configRead` lists its include manifest: the [path] relative to the entry, the
 * file that includes it ([parent]; none for the entry) and how many lines it has.
 */
@Immutable
internal class RvConfigFile(val path: String, val parent: String?, val lines: Int)

/** `configRead`'s summary: what the tree declares, DNS request and response rules counted apart, fallbacks left out. */
@Immutable
internal class RvConfigSummary(
    val subscriptions: Int,
    val nodes: Int,
    val groups: Int,
    val trafficRules: Int,
    val dnsUpstreams: Int,
    val dnsRequestRules: Int,
    val dnsResponseRules: Int,
)

/** Why the app does not apply a declaration or a rule: one support record's reasons, as the page names them. */
internal enum class RvSkipReason(@StringRes val label: Int) {
    LinuxOnly(R.string.rv_config_reason_linux),
    ProcessName(R.string.rv_config_reason_process),
    MacAddress(R.string.rv_config_reason_mac),
}

/**
 * One support record the app skips: the declaration or rule as the file spells it at its location ([source], [line]),
 * and every reason on that record.
 */
@Immutable
internal class RvSkipped(val text: String, val source: String, val line: Int, val reasons: List<RvSkipReason>)

/**
 * The configuration as the overview shows it: the applied revision [rev] and when it was applied, the files, the
 * summary, the records skipped and how many are applied only in part ([constrained]).
 */
@Immutable
internal class RvConfigState(
    val rev: Int,
    val appliedAtEpochMs: Long,
    val files: List<RvConfigFile>,
    val summary: RvConfigSummary,
    val skipped: List<RvSkipped>,
    val constrained: Int = 0,
) {
    /** How many of [skipped] lie in [path]. */
    fun skippedIn(path: String): Int = skipped.count { it.source == path }
}

/** The sample tree: an entry with three Linux-only settings, nodes, two rule files, DNS; generic names only. */
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
    val overview = RvConfigState(REV, APPLIED_AT, files, summary, linuxOnly)

    /** The same with the negative rule in `rules/lan.dae` skipped too. */
    val skippedInclude = RvConfigState(REV, APPLIED_AT, files, summary, linuxOnly + lanRule)
}
