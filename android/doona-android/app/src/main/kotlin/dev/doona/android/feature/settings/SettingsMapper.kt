package dev.doona.android.feature.settings

import dev.doona.android.engine.AppPolicy
import dev.doona.android.engine.ConfigAssets
import dev.doona.android.engine.PerApp
import dev.doona.android.feature.model.RvAppPolicy
import dev.doona.android.feature.model.RvDnsUpstream
import dev.doona.android.feature.model.RvGeoData
import dev.doona.android.feature.model.RvGeoUpdate
import dev.doona.android.feature.model.RvPerApp
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

/** The page's per-app setting for the engine's [perApp]. */
internal fun rvPerApp(perApp: PerApp) = RvPerApp(
    when (perApp.policy) {
        AppPolicy.All -> RvAppPolicy.All
        AppPolicy.OnlyListed -> RvAppPolicy.Only
        AppPolicy.ExceptListed -> RvAppPolicy.Except
    },
    perApp.packages,
)

/**
 * Where a geo update stands for the core's `lastResult` (the set `work/GeoRefresh` reads): still checking, downloading
 * or verifying; nothing newer; newer data, which the core uses from its next start; or failed. Before any update, and
 * for a word the app does not know, the row shows only the date.
 */
internal fun rvGeoUpdate(lastResult: String): RvGeoUpdate = when (lastResult) {
    "checking", "downloading", "verifying" -> RvGeoUpdate.Updating
    "up_to_date" -> RvGeoUpdate.Latest
    "ready_on_next_start", "activated" -> RvGeoUpdate.Updated
    "failed" -> RvGeoUpdate.Failed
    else -> RvGeoUpdate.Idle
}

/**
 * The GeoIP/GeoSite row for the core's [assets]: the active data's release date, the APK's own ([shippedDate]) when the
 * core reports none, could not be read, or reports a release that is not a `YYYY-MM-DD` date (no date at all when the
 * APK's is none either, which the row shows as a dash rather than ending 設定); where an update stands;
 * and whether the VPN is [connected], which an update needs.
 */
internal fun rvGeoData(assets: ConfigAssets?, shippedDate: String, connected: Boolean) = RvGeoData(
    dateEpochMs = assets?.release?.let(::releaseEpochMs) ?: releaseEpochMs(shippedDate),
    update = assets?.let { rvGeoUpdate(it.lastResult) } ?: RvGeoUpdate.Idle,
    connected = connected,
)

/** Noon UTC of a `YYYY-MM-DD` release, so the date reads the same in every time zone the row is shown in; null for any other text. */
private fun releaseEpochMs(date: String): Long? = try {
    LocalDate.parse(date).atTime(LocalTime.NOON).toInstant(ZoneOffset.UTC).toEpochMilli()
} catch (_: DateTimeParseException) {
    null
}

/**
 * The preset the core's DNS [servers] are (`effectiveConfig`'s `dnsUpstreams`: `ip:port`, IPv6 as `[addr]:port`, in
 * the order the core asks them): the first server, on port 53, at a preset's address. Null, which the row shows as
 * 自訂, for any other server, another port, or no server.
 */
internal fun rvDnsUpstream(servers: List<String>): RvDnsUpstream? {
    val first = servers.firstOrNull() ?: return null
    val host = first.removeSuffix(":$DNS_PORT").removeSurrounding("[", "]")
    return RvDnsUpstream.entries.firstOrNull { it.address == host }
}

/** The port a preset's servers answer on, which `dnsUpstreams` writes after each address. */
private const val DNS_PORT = 53

/**
 * What 檢查更新 found for the app's [current] version and the [latest] release tag (null: none published yet, so
 * nothing is newer): [RvUpdate.Available] for a higher version number, [RvUpdate.Latest] otherwise. A tag or a version
 * that starts with no number (`v1.2.3`, `1.2.3-beta` and `1.2` all do) cannot be compared and is [RvUpdate.Failed].
 */
internal fun rvUpdate(current: String, latest: String?): RvUpdate {
    if (latest == null) return RvUpdate.Latest
    val have = versionNumbers(current)
    val newest = versionNumbers(latest)
    if (have == null || newest == null) return RvUpdate.Failed
    return if (compareVersions(newest, have) > 0) RvUpdate.Available(latest.trim().removePrefix("v")) else RvUpdate.Latest
}

private val VERSION_NUMBERS = Regex("""^v?(\d+(?:\.\d+)*)""")

private fun versionNumbers(version: String): List<Long>? =
    VERSION_NUMBERS.find(version.trim())?.groupValues?.get(1)?.split('.')?.map { part -> part.toLongOrNull() ?: return null }

private fun compareVersions(a: List<Long>, b: List<Long>): Int {
    for (i in 0 until maxOf(a.size, b.size)) {
        val order = a.getOrElse(i) { 0 }.compareTo(b.getOrElse(i) { 0 })
        if (order != 0) return order
    }
    return 0
}
