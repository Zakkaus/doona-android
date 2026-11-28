package dev.doona.android.feature.policies

import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.feature.model.RvRuleSource
import dev.doona.android.feature.model.RvTemplate
import dev.doona.android.feature.model.rvTemplate
import dev.doona.android.honk.HonkConfig
import dev.doona.android.honk.HonkDnsRules
import dev.doona.android.honk.HonkSource
import dev.doona.android.honk.daeBlock
import dev.doona.android.honk.daeChildren
import dev.doona.android.honk.daeCode
import dev.doona.android.honk.daeEntries

/**
 * doona's starting configurations: ordinary honk files, whose routing and groups 套用 writes
 * ([HonkRouting.withTemplate]). Their ids are the ones [rvTemplate] names, in the order the template page lists them.
 */
internal object HonkTemplates {
    val ids = listOf("cn-direct", "services", "regions", "homebound", "gfw")

    fun text(id: String): String =
        checkNotNull(HonkTemplates::class.java.getResource("/dev/doona/android/templates/$id.dae")) { "no template $id" }.readText()

    /** The templates as 範本 lists them: one that reads GeoSite categories needs a GeoSite file honk loaded. */
    fun listed(geosite: Boolean): List<RvTemplate> = ids.map { rvTemplate(it, needsGeosite = !geosite && GEOSITE in text(it)) }

    /** Each template's routing entries, read once. */
    val routing: Map<String, List<String>> by lazy { ids.associateWith { routingEntries(text(it)).orEmpty() } }
}

/**
 * The file that holds honk's routing, when one file holds all of it (routing split over files is the user's own): the
 * template whose routing it is, apart from 阻擋 QUIC's rule, and whether that rule is in it.
 */
internal class HonkRouting(val source: HonkSource, val template: String?, val blockQuic: Boolean) {
    /** The file with 阻擋 QUIC's rule first in its routing when [on], and without it when not. */
    fun withBlockQuic(on: Boolean): String {
        val lines = source.content.lines().filterNot { daeCode(it) == QUIC }
        if (!on) return lines.joinToString("\n")
        val body = daeBlock(lines.joinToString("\n"), ROUTING) ?: throw EngineException(ErrorCode.Busy)
        return (lines.take(body.first) + "$INDENT$QUIC" + lines.drop(body.first)).joinToString("\n")
    }

    /**
     * The file with the template [id] in it, as doona web writes one: the template's routing in place of the file's,
     * 阻擋 QUIC kept as it was, and each of the template's groups that no file of the configuration names ([existing])
     * added to its groups. The file's own groups and DNS stay as they are.
     */
    fun withTemplate(id: String, existing: Set<String>): String {
        val template = HonkTemplates.text(id)
        val templateLines = template.lines()
        val added = daeChildren(template, GROUP).filter { it.first !in existing }.flatMap { templateLines.slice(it.second) }
        var lines = source.content.lines()
        val routing = daeBlock(source.content, ROUTING) ?: throw EngineException(ErrorCode.Busy)
        lines = lines.take(routing.first) + templateLines.slice(checkNotNull(daeBlock(template, ROUTING))) + lines.drop(routing.last + 1)
        val groups = daeBlock(lines.joinToString("\n"), GROUP)
        lines = when {
            added.isEmpty() -> lines
            groups == null -> listOf("group {") + added + listOf("}", "") + lines
            else -> lines.take(groups.last + 1) + added + lines.drop(groups.last + 1)
        }
        val written = HonkRouting(HonkSource(source.id, source.path, source.kind, source.sha256, lines.joinToString("\n"), source.writable, lines.size, source.loadedAt), id, false)
        return if (blockQuic) written.withBlockQuic(true) else written.source.content
    }

    companion object {
        fun of(config: HonkConfig): HonkRouting? {
            val holders = config.sources.filter { it.kind == MAIN || it.kind == INCLUDE }.mapNotNull { source ->
                routingEntries(source.content)?.let { source to it }
            }
            val (source, entries) = holders.singleOrNull() ?: return null
            val rules = entries.filterNot { it == QUIC }
            return HonkRouting(source, HonkTemplates.routing.entries.firstOrNull { it.value == rules }?.key, QUIC in entries)
        }
    }
}

/** Where the rules of [routing] come from: the template it is, else the user's own files. */
internal fun honkRuleSource(routing: HonkRouting?): RvRuleSource = routing?.template?.let { RvRuleSource.Template(rvTemplate(it)) } ?: RvRuleSource.File

/** Whether 封鎖廣告's DNS rule is among honk's running request rules. */
internal fun blocksAds(dns: HonkDnsRules): Boolean = dns.request.any { it.expression == ADS }

/**
 * The file of [config] that turns 封鎖廣告 [on] or off, with the file's new text: on puts the rule first in the DNS
 * requests of the file whose request fallback honk runs, off takes it out of the file honk read it from. Null when there
 * is nothing to change.
 */
internal fun adsEdit(config: HonkConfig, dns: HonkDnsRules, on: Boolean): Pair<HonkSource, String>? {
    val sources = config.sources.associateBy { it.id }
    if (!on) {
        val at = dns.request.firstOrNull { it.expression == ADS }?.source ?: return null
        val source = sources[at.sourceId] ?: throw EngineException(ErrorCode.Busy)
        val lines = source.content.lines()
        if (daeCode(lines.getOrNull(at.line - 1).orEmpty()) != ADS) throw EngineException(ErrorCode.Busy)
        return source to lines.filterIndexed { index, _ -> index != at.line - 1 }.joinToString("\n")
    }
    if (blocksAds(dns)) return null
    val at = dns.request.lastOrNull()?.source ?: throw EngineException(ErrorCode.InvalidArgument)
    val source = sources[at.sourceId] ?: throw EngineException(ErrorCode.Busy)
    val lines = source.content.lines()
    val body = daeBlock(source.content, "dns", ROUTING, "request") ?: throw EngineException(ErrorCode.Busy)
    val indent = lines.getOrNull(body.first)?.takeWhile { it == ' ' } ?: "$INDENT$INDENT$INDENT"
    return source to (lines.take(body.first) + "$indent$ADS" + lines.drop(body.first)).joinToString("\n")
}

private fun routingEntries(text: String): List<String>? = daeBlock(text, ROUTING)?.let { daeEntries(text.lines().slice(it)) }

/** 阻擋 QUIC: UDP to port 443 is refused, so apps fall back to TCP (doona web's templates write the same rule). */
private const val QUIC = "l4proto(udp) && dport(443) -> block"

/** 封鎖廣告: ad domains get an empty answer. */
private const val ADS = "qname(geosite:category-ads-all) -> reject"

private const val ROUTING = "routing"
private const val GEOSITE = "geosite:"
private const val GROUP = "group"
private const val MAIN = "main"
private const val INCLUDE = "include"
private const val INDENT = "    "
