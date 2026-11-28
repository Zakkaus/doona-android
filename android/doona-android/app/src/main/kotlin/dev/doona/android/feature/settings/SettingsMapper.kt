package dev.doona.android.feature.settings

import dev.doona.android.engine.AppPolicy
import dev.doona.android.engine.PerApp
import dev.doona.android.feature.model.RvAppPolicy
import dev.doona.android.feature.model.RvDnsUpstream
import dev.doona.android.feature.model.RvGeoData
import dev.doona.android.feature.model.RvPerApp
import java.time.LocalDate
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
 * The GeoIP/GeoSite row for honk's [data], null while honk does not answer: honk's date, else the APK's own
 * ([shippedDate], no date at all when that is not a `YYYY-MM-DD` day, which the row shows as a dash rather than
 * ending 設定); without honk the row says 未連線, as an update needs it.
 */
internal fun rvGeoData(data: RvGeoData?, shippedDate: String) =
    data?.let { it.copy(date = it.date ?: releaseDate(shippedDate)) } ?: RvGeoData(releaseDate(shippedDate), connected = false)

/** The day of a `YYYY-MM-DD` release, kept a calendar day so no time zone moves it; null for any other text. */
private fun releaseDate(date: String): LocalDate? = try {
    LocalDate.parse(date)
} catch (_: DateTimeParseException) {
    null
}

/**
 * The preset the core's DNS [servers] are (`effectiveConfig`'s `dnsUpstreams`: `ip:port`, IPv6 as `[addr]:port`, in
 * the order the core asks them): the first server, on port 53, at a preset's address. Null, which the row shows as
 * 自訂, for any other server, another port, or no server.
 */
internal fun rvDnsUpstream(address: String?): RvDnsUpstream? {
    val host = address?.removeSuffix(":$DNS_PORT")?.removeSurrounding("[", "]") ?: return null
    return RvDnsUpstream.entries.firstOrNull { it.address == host }
}

/** The port a preset's servers answer on, which `dnsUpstreams` writes after each address. */
internal const val DNS_PORT = 53

/** The server `setDnsUpstreams` saves for this preset: its address on [DNS_PORT]. */
internal val RvDnsUpstream.server: String get() = "$address:$DNS_PORT"

/**
 * What 檢查更新 found for the app's [current] version and the [latest] release tag (null: none published yet, so
 * nothing is newer): [RvUpdate.Available] for a higher version number, [RvUpdate.Latest] otherwise. Each is read up
 * to its first non-numeric part after an optional `v` (`v1.2.3`, `1.2.3-beta` and `1.2` all compare); one that does not
 * start with a number cannot be compared and is [RvUpdate.Failed].
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
