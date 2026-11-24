package dev.doona.android.prototype.rv.data

import androidx.compose.runtime.Immutable
import dev.doona.android.designsystem.rv.RvPlace
import dev.doona.android.designsystem.rv.RvSpot
import java.net.URLEncoder
import java.util.Random
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * One node: what its share link says (protocol, server, port, transport, TLS), the subscription it came from, and the
 * results of its last latency tests, at most [RV_TESTS], oldest first (a node new to the app has none or one); null is a
 * test that timed out. [name] is the one name every
 * page shows (`Hong Kong 02 IPLC`); [short] is the same node's country code and number for the narrow tiles (`HK 02`).
 */
@Immutable
internal data class RvNode(
    val id: String,
    val name: String,
    val short: String,
    val place: RvPlace,
    val city: RvCity?,
    val protocol: String,
    val host: String,
    val port: Int,
    val transport: String,
    val tls: Boolean,
    val subscription: String,
    val history: List<Int?>,
) {
    /** Where the maps mark the node: its city when its name gives one, else its country's centroid; none for 其他. */
    val spot: RvSpot? get() = if (place.mapped) city?.spot ?: place.spot else null

    /** The last test's result; null for a test that timed out, and for a node with no test yet. */
    val latencyMs: Int? get() = history.lastOrNull()

    /** The change from the test before, when both answered: positive is slower. */
    val changeMs: Int? get() = history.getOrNull(history.size - 2)?.let { before -> latencyMs?.let { it - before } }

    /** The middle of the answered tests ([rvMedian]); null with fewer than two, where the middle is the test itself and says nothing more. */
    val medianMs: Int? get() = if (answered < 2) null else rvMedian(history.filterNotNull())

    /** How many of the kept tests answered. */
    val answered: Int get() = history.count { it != null }

    /**
     * The last test timed out (every failed test in the sample is a timeout). A node none of whose kept tests answered
     * reads as never tested, as [untested] makes it: the sample keeps no other trace of a test that ran.
     */
    val timedOut: Boolean get() = latencyMs == null && answered > 0

    /**
     * How far the answered tests stray from their mean: their standard deviation in ms. Null with fewer than two answered
     * tests, which say nothing about spread.
     */
    val spreadMs: Double?
        get() {
            val values = history.filterNotNull().takeIf { it.size >= 2 } ?: return null
            val mean = values.average()
            return sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
        }

    /** [spreadMs] over all tests; a node that ever timed out, or has too few tests to tell, is the least steady. */
    val jitterMs: Double get() = if (answered < history.size) Double.MAX_VALUE else spreadMs ?: Double.MAX_VALUE

    /** The same node before any test ran. */
    fun untested(): RvNode = copy(history = List(history.size) { null })
}

/** The [count] fastest nodes by their last test, ties in list order; every page's 最快 list is this one. */
internal fun List<RvNode>.fastest(count: Int): List<RvNode> = filter { it.latencyMs != null }.sortedBy { it.latencyMs }.take(count)

/** The [count] steadiest nodes over all tests. */
internal fun List<RvNode>.steadiest(count: Int): List<RvNode> = sortedBy { it.jitterMs }.take(count)

/** The middle of [values], the mean of the two middle ones when there are an even number; null when there are none. */
internal fun rvMedian(values: List<Int>): Int? = values.sorted().takeIf { it.isNotEmpty() }?.let { (it[(it.size - 1) / 2] + it[it.size / 2]) / 2 }

/** How many latency tests each node keeps. */
internal const val RV_TESTS = 8

/**
 * The node as a share link in the common URI form, `scheme://host:port?type=…&security=…#name`. The sample holds no
 * credentials, so the link carries the server's address and transport only; the app adds the subscription's user id.
 */
internal fun RvNode.shareLink(): String {
    val scheme = if (protocol == "Shadowsocks") "ss" else protocol.lowercase()
    val type = if (transport == "WebSocket") "ws" else transport.lowercase()
    val security = if (tls) "tls" else "none"
    return "$scheme://$host:$port?type=$type&security=$security#" + URLEncoder.encode(name, "UTF-8").replace("+", "%20")
}

/**
 * The 48 nodes in 12 countries from the two subscriptions. Each node's tests come from its own seed: a base for its
 * distance, its own spread, a drift up or down over the eight rounds and now and then a spike or a timeout, so no two
 * histories share a shape. A node is numbered within its country, and the number is in its id, name, short label and
 * host alike (tw-03, Taichung 03, TW 03, tw3.example.net). One node, sg-03, slows down steadily over the last rounds:
 * the detail page's sample.
 */
internal fun rvNodes(): List<RvNode> {
    val perPlace = mutableMapOf<RvPlace, Int>()
    return SPECS.mapIndexed { i, spec ->
        val n = perPlace.merge(spec.city.place, 1, Int::plus)!!
        val number = n.toString().padStart(2, '0')
        val code = spec.city.place.name
        RvNode(
            id = "${code.lowercase()}-$number",
            name = "${spec.city.en} $number${spec.suffix}",
            short = "$code $number",
            place = spec.city.place,
            city = spec.city,
            protocol = spec.protocol.label,
            host = "${code.lowercase()}$n.example.net",
            port = spec.protocol.port,
            transport = spec.protocol.transport,
            tls = spec.protocol.tls,
            subscription = if (i % 3 == 2) RV_LANTERN else RV_HARBOR,
            history = SLOWING.takeIf { code == "SG" && n == 3 } ?: history(i, spec),
        )
    }
}

/** When the [RV_TESTS] rounds ran, oldest first: every half hour, the last at 10:00 UTC. */
internal fun rvTestTimes(): List<Long> = List(RV_TESTS) { LAST_TEST - (RV_TESTS - 1 - it) * TEST_EVERY }

private fun history(index: Int, spec: Spec): List<Int?> {
    val random = Random(SEED + index * SEED_STEP)
    val drift = (random.nextFloat() - 0.5f) * spec.spread
    return List(RV_TESTS) { round ->
        val spike = if (random.nextFloat() < SPIKE_CHANCE) spec.spread * (2 + random.nextInt(3)) else 0
        val noise = random.nextGaussian() * spec.spread / 2
        val value = (spec.base + drift * round / RV_TESTS + noise + spike).roundToInt().coerceAtLeast(spec.base / 2)
        val timedOut = if (round == RV_TESTS - 1) index == TIMED_OUT else random.nextFloat() < TIMEOUT_CHANCE
        value.takeUnless { timedOut }
    }
}

/** A protocol as a share link spells it out: its name, the transport it rides, its usual port and whether it has TLS. */
private enum class Proto(val label: String, val transport: String, val port: Int, val tls: Boolean) {
    Trojan("Trojan", "TCP", 443, true),
    TrojanWs("Trojan", "WebSocket", 443, true),
    Vless("VLESS", "gRPC", 443, true),
    Vmess("VMess", "WebSocket", 8443, true),
    Ss("Shadowsocks", "TCP", 8388, false),
    Hy2("Hysteria2", "QUIC", 443, true),
    Tuic("TUIC", "QUIC", 8443, true),
}

/** A node before its numbers: its city (and so its country), an optional tag, its protocol, its base latency and spread. */
private class Spec(val city: RvCity, val protocol: Proto, val base: Int, val spread: Int, val suffix: String = "")

private val SPECS = listOf(
    Spec(RvCity.HongKong, Proto.Trojan, 38, 10),
    Spec(RvCity.HongKong, Proto.Vless, 31, 6, " IPLC"),
    Spec(RvCity.HongKong, Proto.Ss, 44, 14),
    Spec(RvCity.HongKong, Proto.Hy2, 52, 22),
    Spec(RvCity.HongKong, Proto.TrojanWs, 41, 9, " IEPL"),
    Spec(RvCity.HongKong, Proto.Vmess, 63, 18),
    Spec(RvCity.Taipei, Proto.Trojan, 24, 5),
    Spec(RvCity.Taipei, Proto.Hy2, 29, 12),
    Spec(RvCity.Taichung, Proto.Ss, 35, 8),
    Spec(RvCity.Taipei, Proto.Vless, 27, 4),
    Spec(RvCity.Kaohsiung, Proto.Tuic, 39, 15),
    Spec(RvCity.Tokyo, Proto.Vless, 58, 9),
    Spec(RvCity.Tokyo, Proto.Trojan, 64, 16),
    Spec(RvCity.Osaka, Proto.Hy2, 71, 25),
    Spec(RvCity.Tokyo, Proto.Ss, 66, 11, " IPLC"),
    Spec(RvCity.Osaka, Proto.Vmess, 79, 13),
    Spec(RvCity.Tokyo, Proto.Tuic, 61, 20),
    Spec(RvCity.Seoul, Proto.Trojan, 74, 12),
    Spec(RvCity.Seoul, Proto.Vless, 82, 18),
    Spec(RvCity.Chuncheon, Proto.Ss, 91, 9),
    Spec(RvCity.Singapore, Proto.Trojan, 86, 14),
    Spec(RvCity.Singapore, Proto.Hy2, 97, 28),
    Spec(RvCity.Singapore, Proto.Vless, 92, 10),
    Spec(RvCity.Singapore, Proto.Ss, 104, 12),
    Spec(RvCity.Singapore, Proto.TrojanWs, 99, 17),
    Spec(RvCity.LosAngeles, Proto.Vless, 152, 21),
    Spec(RvCity.SanJose, Proto.Trojan, 161, 15),
    Spec(RvCity.Seattle, Proto.Hy2, 174, 33),
    Spec(RvCity.LosAngeles, Proto.Ss, 158, 12),
    Spec(RvCity.NewYork, Proto.Vmess, 226, 24),
    Spec(RvCity.Dallas, Proto.Tuic, 198, 19),
    Spec(RvCity.Vancouver, Proto.Trojan, 171, 14),
    Spec(RvCity.Toronto, Proto.Vless, 234, 27),
    Spec(RvCity.London, Proto.Trojan, 238, 18),
    Spec(RvCity.London, Proto.Hy2, 251, 40),
    Spec(RvCity.Manchester, Proto.Ss, 262, 15),
    Spec(RvCity.London, Proto.Vless, 244, 11),
    Spec(RvCity.Frankfurt, Proto.Vless, 221, 13),
    Spec(RvCity.Frankfurt, Proto.Trojan, 229, 22),
    Spec(RvCity.Berlin, Proto.Tuic, 247, 31),
    Spec(RvCity.Amsterdam, Proto.Trojan, 232, 16),
    Spec(RvCity.Amsterdam, Proto.Ss, 241, 10),
    Spec(RvCity.Rotterdam, Proto.Vmess, 268, 26),
    Spec(RvCity.Paris, Proto.Vless, 236, 14),
    Spec(RvCity.Marseille, Proto.Hy2, 214, 29),
    Spec(RvCity.Sydney, Proto.Trojan, 128, 17),
    Spec(RvCity.Melbourne, Proto.Vless, 141, 12),
    Spec(RvCity.Sydney, Proto.Ss, 136, 23),
)

/** sg-03's last rounds: fine at first, then slower each time. */
private val SLOWING = listOf(92, 88, 97, 104, 131, 158, 172, 186)

/** New York's last test timed out, so the list shows a node with no figure. */
private const val TIMED_OUT = 29

private const val SEED = 20_261_123L
private const val SEED_STEP = 7_919L
private const val SPIKE_CHANCE = 0.08f
private const val TIMEOUT_CHANCE = 0.03f

/** The last round ran at 2026-11-22 10:00 UTC, 24 minutes before [RV_NOW], every half hour before it. */
private const val LAST_TEST = 1_795_341_600_000L
private const val TEST_EVERY = 1_800_000L
