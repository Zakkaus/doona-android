package dev.doona.android.engine

/** What a user can change in a template: [blockQuic] refuses QUIC (UDP/443) bound for a group, so apps fall back to TCP. */
data class RuleOptions(val blockQuic: Boolean = false)

/**
 * Where [Mode.Rule]'s rules come from: a template by its [template] name and [version], or the subscription
 * [subscriptionId] (schema 2 has templates only). [rules] rows compiled from [lines] values, of which [skipped] lines
 * were not compiled. [rulesRevision] changes exactly when the rows do; compare it for equality only. [error] is
 * [ErrorCode.ParseFailed] when the source did not compile.
 */
data class RuleSource(
    val template: String?,
    val version: Int?,
    val subscriptionId: String?,
    val options: RuleOptions,
    val rules: Int,
    val lines: Int,
    val skipped: Int,
    val rulesRevision: Long,
    val error: ErrorCode? = null,
)

/** A page of rule rows: [total] rows in the source, read at [rulesRevision]. */
data class RulePage(val rulesRevision: Long, val total: Int, val rows: List<RuleRow>)

/**
 * One row of rules, at [index] in first-match order. [id] stays the same across node changes and restarts, and keys
 * [DayUsage.byRule]. [kind] is null for a kind the core does not compile; [kindName] names every kind as the core
 * does, such as `DomainSuffix`, or as the source does, such as `GEOSITE`. [sample] is the first of the row's [count]
 * values, null for [RuleKind.Match]. [skippedLines] of them were not compiled, for the reason [skipped].
 */
data class RuleRow(
    val id: String,
    val index: Int,
    val kind: RuleKind?,
    val kindName: String,
    val sample: String?,
    val count: Int,
    val target: RuleTarget,
    val skipped: RuleSkip? = null,
    val skippedLines: Int = 0,
)

enum class RuleKind { Domain, DomainSuffix, DomainKeyword, IpCidr, GeoIp, DstPort, Network, Match }

/** Where a rule sends a flow: straight out, refused (TCP reset, datagrams dropped), or through a group. */
sealed interface RuleTarget {
    data object Direct : RuleTarget

    data object Reject : RuleTarget

    data class Group(val id: String) : RuleTarget
}

/** Why lines of a row were not compiled: a matcher doona lacks, GeoIP or GeoSite data it lacks, or an app rule (ARCH D24). */
enum class RuleSkip { UnsupportedKind, GeodataMissing, AppRule }

/**
 * What routes flows and DNS now: DNS goes [dnsVia] ([RuleTarget.Direct] in Direct, else the main group) to
 * [dnsUpstreams]. [text] is a Clash-style export, cut at a line boundary past 1 MiB when [truncated]. It names a node
 * only by its code (ARCH D21): show it on screen through [NodeCodes], and export or share it as it is.
 */
data class EffectiveConfig(
    val mode: Mode,
    val mainGroupId: String,
    val dnsVia: RuleTarget,
    val dnsUpstreams: List<String>,
    val ruleSource: RuleSource,
    val text: String,
    val truncated: Boolean,
)
