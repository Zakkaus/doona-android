package dev.doona.android.prototype.rv.stats

import dev.doona.android.engine.Member
import dev.doona.android.engine.Subscription
import java.util.Random

/**
 * The 統計 sample at 15:40 UTC: sixteen hours of a day whose download, upload and connections each keep their own shape
 * (a night with a photo backup that uploads and opens few connections, a morning rise, a video-heavy lunch), three
 * subscriptions, and 48 nodes in twelve countries named in each way subscriptions spell a place. Seeded, so the golden
 * stays the same; every name and figure is invented.
 */
internal fun rvStatsSample(): RvStatsState {
    val random = Random(SEED)
    val down = DOWN_MB.map { mb -> jitter(mb * MB, random) }
    val up = UP_MB.map { mb -> jitter(mb * MB, random) }
    val connections = CONNECTIONS.map { n -> jitter(n.toDouble(), random).toInt() }
    return RvStatsState(
        range = RvStatsRange.Today,
        today = RvStatsDay(down, up, connections),
        yesterdaySoFar = YESTERDAY_SO_FAR,
        subscriptions = SUBSCRIPTIONS,
        nodes = sampleNodes(random),
        selectedId = "n0",
        split = RvSplit(proxyBytes = 2_340_000_000, directBytes = 1_100_000_000),
        nowEpochMs = NOW,
    )
}

/** The same page before the first byte of the day, with no subscription quota and no test run yet. */
internal fun rvStatsQuiet(): RvStatsState {
    val hours = DOWN_MB.size
    val nodes = rvStatsSample().nodes.map { it.copy(latencyMs = null) }
    return RvStatsState(
        range = RvStatsRange.Today,
        today = RvStatsDay(List(hours) { 0L }, List(hours) { 0L }, null),
        yesterdaySoFar = null,
        subscriptions = emptyList(),
        nodes = nodes,
        selectedId = "n0",
        split = null,
        nowEpochMs = NOW,
    )
}

private fun jitter(value: Double, random: Random): Long = (value * (1 + JITTER * (2 * random.nextDouble() - 1))).toLong()

/** Each country's nodes as their subscription names them, with the latency its distance gives, ±25 %. */
private fun sampleNodes(random: Random): List<Member> {
    var index = 0
    return COUNTRIES.flatMap { (base, subscription, names) ->
        names.map { name ->
            val latency = when {
                index == 0 -> SELECTED_MS
                name in UNTESTED -> null
                else -> (base * (1 + LATENCY_SPREAD * (2 * random.nextDouble() - 1))).toInt()
            }
            Member(id = "n${index++}", name = name, latencyMs = latency, failed = name == FAILED, subscriptionId = subscription)
        }
    }
}

/** A country's typical latency in ms, the subscription its nodes come from, and their names. */
private data class Country(val baseMs: Int, val subscription: String, val names: List<String>)

private val COUNTRIES = listOf(
    Country(40, "s1", listOf("hk-01", "🇭🇰 HK 02 IPLC", "HK 03", "Hong Kong 04", "hk-05", "🇭🇰 HK 06", "HK 07 BGP", "hk-08")),
    Country(78, "s1", listOf("Tokyo 01", "🇯🇵 Osaka", "Tokyo 02", "jp-03", "JP 04", "🇯🇵 Tokyo 05", "Osaka 06")),
    Country(58, "s1", listOf("Taipei HiNet", "tw-02", "🇹🇼 TW 03", "Taiwan 04", "tw-05")),
    Country(96, "s1", listOf("sg-02", "Singapore 01", "🇸🇬 SG 03", "sg-04", "SG 05")),
    Country(170, "s1", listOf("us-lax-01", "🇺🇸 San Jose", "US 02", "us-sea-03", "Los Angeles 04", "🇺🇸 US 05")),
    Country(88, "s1", listOf("Seoul 01", "kr-02", "🇰🇷 KR 03")),
    Country(215, "s2", listOf("🇬🇧 London 01", "🇬🇧 London 02", "🇬🇧 UK 03")),
    Country(225, "s2", listOf("🇩🇪 Frankfurt 01", "🇩🇪 Frankfurt 02", "🇩🇪 DE 03")),
    Country(220, "s2", listOf("🇳🇱 Amsterdam 01", "🇳🇱 NL 02")),
    Country(190, "s2", listOf("🇨🇦 Toronto 01", "🇨🇦 CA 02")),
    Country(150, "s3", listOf("🇦🇺 Sydney 01", "🇦🇺 AU 02")),
    Country(230, "s3", listOf("🇫🇷 Paris 01", "🇫🇷 FR 02")),
)

private val UNTESTED = setOf("HK 07 BGP", "us-sea-03", "🇩🇪 DE 03")
private const val FAILED = "us-sea-03"
private const val SELECTED_MS = 42
private const val LATENCY_SPREAD = 0.25

/** Megabytes per hour from midnight to the current hour, which has run 40 minutes. */
private val DOWN_MB = listOf(38.0, 12.0, 6.0, 4.0, 5.0, 9.0, 22.0, 140.0, 260.0, 210.0, 180.0, 230.0, 610.0, 460.0, 300.0, 170.0)
private val UP_MB = listOf(4.0, 3.0, 180.0, 240.0, 60.0, 3.0, 5.0, 14.0, 22.0, 18.0, 95.0, 70.0, 26.0, 20.0, 16.0, 9.0)
private val CONNECTIONS = listOf(42, 18, 31, 29, 12, 9, 26, 148, 196, 171, 158, 183, 214, 205, 167, 98)
private const val MB = 1_000_000.0
private const val JITTER = 0.08
private const val SEED = 20261122L

/** Both directions yesterday by 15:40. */
private const val YESTERDAY_SO_FAR = 2_880_000_000L

private val SUBSCRIPTIONS = listOf(
    Subscription("s1", "Harbor Cloud", "https://example.invalid/harbor", 34, UPDATED, usedBytes = 62_400_000_000, totalBytes = 200_000_000_000, expiresAtEpochMs = 1_805_112_000_000),
    Subscription("s2", "Northwind", "https://example.invalid/northwind", 10, UPDATED, usedBytes = 41_800_000_000, totalBytes = 100_000_000_000, expiresAtEpochMs = 1_798_718_400_000),
    Subscription("s3", "Pocket Relay", "https://example.invalid/pocket", 4, UPDATED, usedBytes = 7_300_000_000, totalBytes = 20_000_000_000),
)

/** 2026-11-22 15:40 UTC; the subscriptions were refreshed at 06:00. */
private const val NOW = 1_795_362_000_000L
private const val UPDATED = 1_795_327_200_000L
