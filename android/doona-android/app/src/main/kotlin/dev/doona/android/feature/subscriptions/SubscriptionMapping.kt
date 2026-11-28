package dev.doona.android.feature.subscriptions

import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.Subscription
import dev.doona.android.engine.SubscriptionFormat
import dev.doona.android.feature.model.RvSubFormat
import dev.doona.android.feature.model.RvSubscription
import dev.doona.android.honk.Provider
import dev.doona.android.honk.errorCode
import java.net.URI
import java.time.OffsetDateTime

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

/**
 * honk's [provider] as the RV pages show it. Used is upload plus download from the `subscription-userinfo` header; a
 * provider that sent none leaves used and total at 0, which the pages read as no quota ([RvSubscription.hasQuota]).
 * honk names no body format, so [RvSubscription.format] is null. [userAgent] is the entry's `ua` in honk's
 * configuration, null for honk's default. The link is reduced to its host, since the rest of a link carries its token.
 */
internal fun rvSubscription(provider: Provider, userAgent: String?): RvSubscription = RvSubscription(
    id = provider.id,
    name = provider.name,
    nodes = provider.nodeCount,
    updatedAtEpochMs = provider.updatedAt?.let(::epochMs),
    usedBytes = provider.traffic?.let { (it.uploadBytes?.toLong() ?: 0L) + (it.downloadBytes?.toLong() ?: 0L) } ?: 0L,
    totalBytes = provider.traffic?.totalBytes?.toLong() ?: 0L,
    expiresAtEpochMs = provider.expiresAt?.let(::epochMs),
    host = provider.urlRedacted?.let(::subscriptionHost).orEmpty(),
    // honk-gap:sub-format-ua
    format = null,
    userAgent = userAgent,
    lastFetchError = provider.lastError?.errorCode(),
)

private fun epochMs(timestamp: String): Long = OffsetDateTime.parse(timestamp).toInstant().toEpochMilli()

internal fun rvSubFormat(format: SubscriptionFormat): RvSubFormat = when (format) {
    SubscriptionFormat.Clash -> RvSubFormat.Clash
    SubscriptionFormat.SingBox -> RvSubFormat.SingBox
    SubscriptionFormat.Sip008 -> RvSubFormat.Sip008
    SubscriptionFormat.ShareLinks, SubscriptionFormat.Base64 -> RvSubFormat.Links
}

/** Use the import-specific explanations for content and network failures, and the engine's reason for other refusals. */
internal fun rvAddFailure(code: ErrorCode): RvAddFailure = when (code) {
    ErrorCode.TooLarge -> RvAddFailure.TooLarge
    ErrorCode.ParseFailed, ErrorCode.NoNodes -> RvAddFailure.NotSubscription
    ErrorCode.FetchFailed, ErrorCode.DnsFailed -> RvAddFailure.Unreachable
    else -> RvAddFailure.NotSaved(code)
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
