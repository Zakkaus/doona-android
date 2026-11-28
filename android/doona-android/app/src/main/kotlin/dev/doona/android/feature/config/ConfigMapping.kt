package dev.doona.android.feature.config

import dev.doona.android.feature.policies.HonkPolicyRead
import dev.doona.android.feature.policies.honkUpstreams
import dev.doona.android.vpn.honk.HonkConfig
import dev.doona.android.vpn.honk.HonkDnsRules
import dev.doona.android.vpn.honk.HonkRevision
import java.time.Instant

/** The text of the UTF-8 bytes `[start, end)` of [text]. */
internal fun rvSpanText(text: String, start: Int, end: Int): String = text.encodeToByteArray().decodeToString(start, end)

/**
 * honk's files as the pages list them: the entry, then the includes in load order. honk does not say which file names
 * an include, so each sits under the entry.
 */
internal fun rvConfigFiles(config: HonkConfig): List<RvConfigFile> {
    val entry = config.main
    return config.files.map { RvConfigFile(it.path, if (it === entry) null else entry.path, it.lineCount) }
}

/**
 * The overview of honk's accepted [config], loaded when its entry was: its files, what honk runs ([policies], the DNS
 * servers of the files, the DNS rules [dns]); honk applies every declaration it accepted, so nothing is skipped.
 */
internal fun rvConfigState(config: HonkConfig, policies: HonkPolicyRead, dns: HonkDnsRules, nowEpochMs: Long): RvConfigState = RvConfigState(
    rev = config.revision,
    appliedAtEpochMs = Instant.parse(config.main.loadedAt).toEpochMilli(),
    files = rvConfigFiles(config),
    summary = RvConfigSummary(
        subscriptions = policies.subscriptions.size,
        nodes = policies.nodes.size,
        groups = policies.groups.size,
        trafficRules = policies.rules.rules.count { !it.isFallback },
        dnsUpstreams = honkUpstreams(config).size,
        dnsRequestRules = dns.request.size,
        dnsResponseRules = dns.response.size,
    ),
    skipped = emptyList(),
    nowEpochMs = nowEpochMs,
)

/** A revision of honk's configuration db: `import`, `activate` (a restore) or `write` (an edit); honk keeps no names. */
internal fun rvRevision(revision: HonkRevision): RvRevision = RvRevision(
    rev = revision.revision.toInt(),
    appliedAtEpochMs = Instant.parse(revision.createdAt).toEpochMilli(),
    origin = when (revision.origin) {
        "import" -> RvRevisionOrigin.Import
        "activate" -> RvRevisionOrigin.Restore
        else -> RvRevisionOrigin.Edit
    },
)
