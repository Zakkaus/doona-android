package dev.doona.android.report

import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.NodeInfo
import java.net.InetAddress
import java.net.URI
import java.net.URISyntaxException

/** Disclosure choices for values already available to the report. */
data class RedactionOptions(
    val showNodeEndpoints: Boolean = false,
    val showOwnAddresses: Boolean = false,
    val showVisitedDomains: Boolean = false,
    val showAppNames: Boolean = true,
)

/** One disclosure boundary for a report's ZIP and issue link. */
internal class ReportScrubber(private val options: RedactionOptions) {
    fun endpoint(node: NodeInfo): String? {
        val server = node.server.removeSurrounding("[", "]")
        val host = if (':' in server) {
            if (!isIpv6(server)) return null
            "[$server]"
        } else {
            dnsName(server) ?: return null
        }
        if (node.port !in 1..65535) return null
        return "$host:${node.port}"
    }

    fun ownAddress(address: InetAddress): String = address.hostAddress!!.substringBefore('%')

    // Require a qualified name: a single label could be an opaque token.
    fun domain(event: AppEvent): String? = if (options.showVisitedDomains) {
        event.visitedDomain?.takeIf { it.indexOf('.') in 1 until it.lastIndex }?.let(::dnsName)
    } else {
        null
    }

    fun appName(event: AppEvent): String? = if (options.showAppNames) {
        event.appPackage?.takeIf { it.length <= 255 && APP_PACKAGE.matches(it) }
    } else {
        null
    }

    fun action(event: AppEvent): String? = event.text.takeIf(actionText::matches)

    fun eventText(event: AppEvent): String = action(event)
        ?: event.text.takeIf { it in APP_MESSAGES || READ_EVENT.matches(it) || FAILURE_EVENT.matches(it) }
        ?: REDACTED

    fun command(text: String): String = text.takeIf { it in REPORT_COMMANDS } ?: UNKNOWN

    fun version(text: String?): String = text?.takeIf(VERSION_NAME::matches) ?: UNKNOWN

    fun metadata(text: String): String = text.takeIf(METADATA::matches) ?: UNKNOWN
}

private fun isIpv6(text: String): Boolean {
    if (!IPV6_TEXT.matches(text)) return false
    return try {
        URI("http://[$text]").host != null
    } catch (_: URISyntaxException) {
        false
    }
}

private fun dnsName(text: String): String? = text.takeIf { it.length <= 254 && DNS_NAME.matches(it) }

private const val LABEL = "[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?"
private val DNS_NAME = Regex("$LABEL(?:\\.$LABEL)*\\.?")
private val IPV6_TEXT = Regex("[0-9a-fA-F:.]{2,45}")
private val APP_PACKAGE = Regex("[a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z][a-zA-Z0-9_]*)+")
private val METADATA = Regex("""[\p{L}\p{N} ._()+-]{1,64}""")
private const val READ_COMMANDS = "trafficSeries|usage|nodes|scoreState|rules|effectiveConfig|diagnostics|recentFlows"
internal val REPORT_COMMANDS = (ACTION_COMMANDS + "|" + READ_COMMANDS).split('|').toSet()
private val READ_EVENT = Regex("(?:$READ_COMMANDS|$ACTION_COMMANDS): (?:${ACTION_CODES.joinToString("|")}) after \\d{1,19} ms")
private const val EXCEPTION_CLASS = "[A-Z][A-Za-z0-9]*"
private val FAILURE_EVENT = Regex(
    "(?:init failed: |(?:$ACTION_COMMANDS|$READ_COMMANDS|snapshot|setPerApp|startForeground) threw |" +
        "(?:$ACTION_COMMANDS|$READ_COMMANDS) reply unreadable: |(?:per-app policy|snapshot) unreadable: |tile connect refused: )$EXCEPTION_CLASS|" +
        "(?:subscription-refresh: (?:settings read|schedule write)|drop notification: settings read) failed \\($EXCEPTION_CLASS\\)|" +
        "subscription-refresh: update failed \\((?:${ErrorCode.entries.joinToString("|") { it.name }})\\)",
)
private val APP_MESSAGES = setOf(
    "health check: data path down",
    TCP_FLOW_EVENT,
    UDP_FLOW_EVENT,
    ExportTokens.PICK_UNSAVED,
    ExportTokens.TOKENS_UNDECODABLE,
)
