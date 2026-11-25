package dev.doona.android.feature.model

import androidx.compose.runtime.Immutable

/**
 * A subscription: its name, the nodes it gave, when it was refreshed, its quota used of total, when it ends, if it does,
 * the host of its link (the page shows only the host: the rest of a link carries its token), how its body is written,
 * and the User-Agent it is fetched with, null for doona's default. The refresh time and the format are both null while
 * it has never been fetched.
 */
@Immutable
internal data class RvSubscription(
    val id: String,
    val name: String,
    val nodes: Int,
    val updatedAtEpochMs: Long?,
    val usedBytes: Long,
    val totalBytes: Long,
    val expiresAtEpochMs: Long?,
    val host: String = "",
    val format: RvSubFormat? = RvSubFormat.Links,
    val userAgent: String? = null,
) {
    /** Whether the provider sent a quota; the sample marks none as a total of 0, as 統計 and 訂閱's bar already read it. */
    val hasQuota: Boolean get() = totalBytes > 0L

    /** What is left, never below nothing when a subscription has gone over its quota. */
    val leftBytes: Long get() = (totalBytes - usedBytes).coerceAtLeast(0L)
}

/** How a subscription's body is written: Clash YAML, sing-box JSON, SIP008 JSON (Shadowsocks servers), or a list of share links (plain or base64). */
internal enum class RvSubFormat { Clash, SingBox, Sip008, Links }
