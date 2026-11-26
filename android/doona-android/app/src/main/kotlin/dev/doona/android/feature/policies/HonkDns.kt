package dev.doona.android.feature.policies

import dev.doona.android.vpn.honk.HonkConfig
import dev.doona.android.vpn.honk.HonkDnsRules
import dev.doona.android.vpn.honk.HonkSource
import dev.doona.android.vpn.honk.daeBlock
import dev.doona.android.vpn.honk.daeCode
import java.net.URI

/**
 * A DNS server a honk file declares in `dns { upstream { name: 'address' [-> via] } }`; with no [via] it goes direct.
 * It is written on [line] (0-based) of [source].
 */
internal class HonkUpstream(val name: String, val address: String, val via: String?, val source: HonkSource, val line: Int)

/** The DNS servers [config]'s main and included files declare, in file order. */
internal fun honkUpstreams(config: HonkConfig): List<HonkUpstream> = upstreamFiles(config).flatMap { source ->
    val lines = source.content.lines()
    daeBlock(source.content, "dns", "upstream")?.mapNotNull { index ->
        UPSTREAM.matchEntire(daeCode(lines[index]))?.let { HonkUpstream(it.groupValues[1], it.groupValues[3], it.groupValues[4].ifEmpty { null }, source, index) }
    }.orEmpty()
}

/** The server most queries go to: the one the running request fallback names. */
internal fun fallbackUpstream(upstreams: List<HonkUpstream>, dns: HonkDnsRules): HonkUpstream? =
    dns.request.lastOrNull { it.isFallback }?.upstream?.let { name -> upstreams.firstOrNull { it.name == name } }

/** A server's address as the pages show it: its URI's host and port, else the address as written. */
internal fun dnsShown(address: String): String = runCatching { URI(address).authority }.getOrNull() ?: address

/** How many of the running DNS rules, fallbacks included, send to the server named [name]. */
internal fun dnsRulesNaming(dns: HonkDnsRules, name: String): Int = (dns.request + dns.response).count { it.upstream == name }

/** [upstream]'s file with the server at [address] instead, its name, route and indentation kept. */
internal fun upstreamReaddressed(upstream: HonkUpstream, address: String): String = upstream.source.content.lines().toMutableList().apply {
    val line = this[upstream.line]
    val indent = line.takeWhile(Char::isWhitespace)
    this[upstream.line] = indent + upstreamLine(upstream.name, address, upstream.via)
}.joinToString("\n")

/** [upstream]'s file without the line that declares it. */
internal fun upstreamRemoved(upstream: HonkUpstream): String =
    upstream.source.content.lines().filterIndexed { index, _ -> index != upstream.line }.joinToString("\n")

/**
 * The file and its new text with a server [name] at [address], going direct, added last to the first `dns.upstream`
 * block of [config]'s main and included files; null when none has one (honk then asks its built-in server).
 */
internal fun upstreamAdded(config: HonkConfig, name: String, address: String): Pair<HonkSource, String>? {
    val source = upstreamFiles(config).firstOrNull { daeBlock(it.content, "dns", "upstream") != null } ?: return null
    val body = checkNotNull(daeBlock(source.content, "dns", "upstream"))
    val lines = source.content.lines().toMutableList()
    val opener = lines[body.first - 1].takeWhile(Char::isWhitespace)
    val indent = body.lastOrNull { daeCode(lines[it]).isNotEmpty() }?.let { lines[it].takeWhile(Char::isWhitespace) } ?: "$opener    "
    lines.add(body.last + 1, indent + upstreamLine(name, address, null))
    return source to lines.joinToString("\n")
}

private fun upstreamFiles(config: HonkConfig) = config.sources.filter { it.kind == "main" || it.kind == "include" }

private fun upstreamLine(name: String, address: String, via: String?) = "$name: '$address'" + (via?.let { " -> $it" } ?: "")

private val UPSTREAM = Regex("""^([^:\s]+)\s*:\s*(['"])(.*)\2\s*(?:->\s*(\S+))?$""")
