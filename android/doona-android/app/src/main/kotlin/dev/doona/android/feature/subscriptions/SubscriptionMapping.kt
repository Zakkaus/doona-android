package dev.doona.android.feature.subscriptions

import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.Subscription
import dev.doona.android.engine.SubscriptionFormat
import dev.doona.android.feature.model.RvSubFormat
import dev.doona.android.feature.model.RvSubscription
import java.net.URI

/**
 * The engine's subscription as the RV pages show it. A provider that sends no quota leaves used and total at 0, which
 * the pages read as no quota ([RvSubscription.hasQuota]); a subscription never fetched has no update time and no
 * format. The link is reduced to its host, since the rest of a link carries its token.
 */
internal fun rvSubscription(sub: Subscription): RvSubscription = RvSubscription(
    id = sub.id,
    name = sub.name,
    nodes = sub.nodes,
    updatedAtEpochMs = sub.lastFetch?.atEpochMs,
    usedBytes = sub.usedBytes ?: 0L,
    totalBytes = sub.totalBytes ?: 0L,
    expiresAtEpochMs = sub.expiresAtEpochMs,
    host = subscriptionHost(sub.url),
    format = sub.lastFetch?.let { rvSubFormat(it.format) },
    userAgent = sub.userAgent,
)

internal fun rvSubFormat(format: SubscriptionFormat): RvSubFormat = when (format) {
    SubscriptionFormat.Clash -> RvSubFormat.Clash
    SubscriptionFormat.SingBox -> RvSubFormat.SingBox
    SubscriptionFormat.Sip008 -> RvSubFormat.Sip008
    SubscriptionFormat.ShareLinks, SubscriptionFormat.Base64 -> RvSubFormat.Links
}

/**
 * Why an import failed, in the three reasons the page tells apart: the body was over the core's bound; the body was
 * read but held no subscription ([ErrorCode.ParseFailed], [ErrorCode.NoNodes]); or anything else, which leaves the
 * link and the network to check, the local network refused included.
 */
internal fun rvAddFailure(code: ErrorCode): RvAddFailure = when (code) {
    ErrorCode.TooLarge -> RvAddFailure.TooLarge

    ErrorCode.ParseFailed, ErrorCode.NoNodes -> RvAddFailure.NotSubscription

    ErrorCode.InvalidArgument, ErrorCode.NotFound, ErrorCode.FetchFailed, ErrorCode.Busy, ErrorCode.Internal,
    ErrorCode.LocalNetwork, ErrorCode.DnsFailed,
    -> RvAddFailure.Unreachable
}

/**
 * A protocol's name as a share link's reader knows it, from the core's lower-case scheme (`vless`, `hysteria2`); one
 * this build does not know keeps the core's spelling.
 */
internal fun rvProtocolLabel(protocol: String): String = PROTOCOL_LABELS[protocol.lowercase()] ?: protocol

/** How many of [protocols] speak each protocol, most first; equal counts keep the order they first appear in. */
internal fun rvProtocolCounts(protocols: List<String>): List<RvProtocolCount> =
    protocols.map(::rvProtocolLabel).groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { RvProtocolCount(it.key, it.value) }

/**
 * The host of a subscription's link as written, without user info or port. [URI.getHost] is null for an
 * internationalized name such as `bücher.example`, which [urlProblem] accepts, so the host is cut from the authority.
 */
internal fun subscriptionHost(url: String): String {
    val authority = runCatching { URI(url.trim()).authority }.getOrNull() ?: return ""
    val host = authority.substringAfterLast('@')
    return if (host.startsWith('[')) host.substringBefore(']') + "]" else host.substringBefore(':')
}

private val PROTOCOL_LABELS = mapOf(
    "trojan" to "Trojan",
    "vless" to "VLESS",
    "vmess" to "VMess",
    "ss" to "Shadowsocks",
    "shadowsocks" to "Shadowsocks",
    "hysteria2" to "Hysteria2",
    "hy2" to "Hysteria2",
    "tuic" to "TUIC",
    "socks5" to "SOCKS5",
    "http" to "HTTP",
)
