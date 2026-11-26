package dev.doona.android.feature.report

import androidx.compose.runtime.Immutable

/** Where the report stands: being written, written with the [RvRedaction] it hides and ready to share, or not written. */
@Immutable
internal sealed interface RvReportBuild {
    data object Building : RvReportBuild

    data class Ready(val redaction: RvRedaction) : RvReportBuild

    data object Failed : RvReportBuild
}

/**
 * What the report hides on top of what it always removes (subscription links, tokens): the phone's own IPs, the
 * domains it visited, and which app opened each connection. The first two start hidden, the app names shown (the
 * owner, owner-fix2 item 11). No switch for node addresses: honk's nodes name no server (owner, 2026-11-26, q-k4-endpoints).
 */
@Immutable
internal data class RvRedaction(val ip: Boolean = true, val domains: Boolean = true, val apps: Boolean = false)

/** What the report holds, as its preview lists it: the app's version, the phone, the settings in counts, and the log. */
@Immutable
internal data class RvReportPreview(
    val app: String,
    val device: String,
    val nodes: Int,
    val subscriptions: Int,
    val rules: Int,
    val events: Int,
    val crashes: Int,
)
