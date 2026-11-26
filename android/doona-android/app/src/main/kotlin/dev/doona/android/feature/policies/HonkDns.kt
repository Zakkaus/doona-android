package dev.doona.android.feature.policies

import dev.doona.android.vpn.honk.HonkConfig
import dev.doona.android.vpn.honk.HonkDnsRules
import dev.doona.android.vpn.honk.daeBlock
import dev.doona.android.vpn.honk.daeEntries
import java.net.URI

/** A DNS server a honk file declares in `dns { upstream { name: 'address' [-> via] } }`; with no [via] it goes direct. */
internal class HonkUpstream(val name: String, val address: String, val via: String?)

/** The DNS servers [config]'s main and included files declare, in file order. */
internal fun honkUpstreams(config: HonkConfig): List<HonkUpstream> = config.sources.filter { it.kind == "main" || it.kind == "include" }
    .flatMap { source ->
        val body = daeBlock(source.content, "dns", "upstream") ?: return@flatMap emptyList()
        daeEntries(source.content.lines().slice(body)).mapNotNull { entry ->
            UPSTREAM.matchEntire(entry)?.let { HonkUpstream(it.groupValues[1], it.groupValues[3], it.groupValues[4].ifEmpty { null }) }
        }
    }

/** The server most queries go to: the one the running request fallback names. */
internal fun fallbackUpstream(upstreams: List<HonkUpstream>, dns: HonkDnsRules): HonkUpstream? =
    dns.request.lastOrNull { it.isFallback }?.upstream?.let { name -> upstreams.firstOrNull { it.name == name } }

/** A server's address as the pages show it: its URI's host and port, else the address as written. */
internal fun dnsShown(address: String): String = runCatching { URI(address).authority }.getOrNull() ?: address

private val UPSTREAM = Regex("""^([^:\s]+)\s*:\s*(['"])(.*)\2\s*(?:->\s*(\S+))?$""")
