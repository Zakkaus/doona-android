package dev.doona.android.report

import dev.doona.android.engine.NodeInfo
import java.net.InetAddress
import java.net.URI
import java.net.URISyntaxException

/** Opt-in disclosure for values already available to the report. */
data class RedactionOptions(
    val showNodeEndpoints: Boolean = false,
    val showOwnAddresses: Boolean = false,
    val showVisitedDomains: Boolean = false,
    val showAppNames: Boolean = false,
)

/** One disclosure boundary per ZIP or issue link. Only typed identifiers may use a disclosure switch. */
internal class ReportScrubber(private val options: RedactionOptions) {
    private val hosts = Aliases("host")
    private val addresses = Aliases("ip")
    private val apps = Aliases("app")

    fun endpoint(node: NodeInfo): String? {
        val server = node.server.removeSurrounding("[", "]")
        val host = if (':' in server) {
            if (!isIpv6(server)) return null
            "[$server]"
        } else {
            dnsName(server) ?: return null
        }
        if (node.port !in 1..65535) return null
        return hosts.render("$host:${node.port}", options.showNodeEndpoints)
    }

    fun ownAddress(address: InetAddress) = addresses.render(address.hostAddress!!.substringBefore('%'), options.showOwnAddresses)

    fun domain(event: AppEvent): String? = event.visitedDomain?.let(::dnsName)?.let { hosts.render(it, options.showVisitedDomains) }

    fun appName(event: AppEvent): String? = event.appPackage?.takeIf { it.length <= 255 && APP_PACKAGE.matches(it) }
        ?.let { apps.render(it, options.showAppNames) }

    fun action(event: AppEvent): String? = event.text.takeIf(actionText::matches)

    fun eventText(event: AppEvent): String = action(event)
        ?: event.text.takeIf { it in APP_MESSAGES || READ_EVENT.matches(it) }
        ?: REDACTED

    fun command(text: String): String = text.takeIf { it in REPORT_COMMANDS } ?: UNKNOWN

    fun errorCode(text: String): String = text.takeIf { it in ACTION_CODES || it in CORE_ERROR_CODES } ?: UNKNOWN

    fun version(text: String?): String = text?.takeIf(VERSION_NAME::matches) ?: UNKNOWN

    fun metadata(text: String): String = text.takeIf(METADATA::matches) ?: UNKNOWN

    private class Aliases(private val prefix: String) {
        private val values = HashMap<String, String>()

        fun render(value: String, show: Boolean): String = if (show) value else values.getOrPut(value) { "$prefix-${values.size + 1}" }
    }
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
private const val READ_COMMANDS = "trafficSeries|usage|nodes|scoreState|rules|effectiveConfig|diagnostics"
internal val REPORT_COMMANDS = (ACTION_COMMANDS + "|" + READ_COMMANDS).split('|').toSet()
private val READ_EVENT = Regex("(?:$READ_COMMANDS|$ACTION_COMMANDS): (?:${ACTION_CODES.joinToString("|")}) after \\d{1,19} ms")
private val APP_MESSAGES = setOf(
    "health check: data path down",
    ExportTokens.PICK_UNSAVED,
    ExportTokens.TOKENS_UNDECODABLE,
)
