package dev.doona.android.prototype.rv.nodes

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import dev.doona.android.R
import java.util.Random
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** The parts of the world the node list groups its rows by. */
internal enum class RvNodesRegion(@StringRes val label: Int) {
    Asia(R.string.rv_nodes_region_asia),
    Oceania(R.string.rv_nodes_region_oceania),
    Americas(R.string.rv_nodes_region_americas),
    Europe(R.string.rv_nodes_region_europe),
}

/** A country or region a node is in, by its ISO code (for its flag): its name, its part of the world, where it is. */
internal enum class RvNodesPlace(@StringRes val label: Int, val region: RvNodesRegion, val lat: Float, val lon: Float) {
    HK(R.string.rv_nodes_place_hk, RvNodesRegion.Asia, 22.35f, 114.15f),
    TW(R.string.rv_nodes_place_tw, RvNodesRegion.Asia, 23.7f, 120.96f),
    JP(R.string.rv_nodes_place_jp, RvNodesRegion.Asia, 36.2f, 138.25f),
    KR(R.string.rv_nodes_place_kr, RvNodesRegion.Asia, 35.9f, 127.8f),
    SG(R.string.rv_nodes_place_sg, RvNodesRegion.Asia, 1.35f, 103.82f),
    US(R.string.rv_nodes_place_us, RvNodesRegion.Americas, 39.83f, -98.58f),
    CA(R.string.rv_nodes_place_ca, RvNodesRegion.Americas, 56.13f, -106.35f),
    GB(R.string.rv_nodes_place_gb, RvNodesRegion.Europe, 54f, -2f),
    DE(R.string.rv_nodes_place_de, RvNodesRegion.Europe, 51.17f, 10.45f),
    NL(R.string.rv_nodes_place_nl, RvNodesRegion.Europe, 52.13f, 5.29f),
    FR(R.string.rv_nodes_place_fr, RvNodesRegion.Europe, 46.23f, 2.21f),
    AU(R.string.rv_nodes_place_au, RvNodesRegion.Oceania, -25.27f, 133.78f),
}

/**
 * One node of the sample: what its share link says (protocol, server, port, transport, TLS) and the subscription it came
 * from, with the results of the last [RV_NODES_TESTS] latency tests, oldest first; null is a test that timed out.
 */
@Immutable
internal class RvNode(
    val id: String,
    val name: String,
    val short: String,
    val place: RvNodesPlace,
    val protocol: String,
    val host: String,
    val port: Int,
    val transport: String,
    val tls: Boolean,
    val subscription: String,
    val history: List<Int?>,
) {
    /** The last test's result. */
    val latencyMs: Int? get() = history.last()

    /** The change from the test before, when both answered: positive is slower. */
    val changeMs: Int? get() = history[history.size - 2]?.let { before -> latencyMs?.let { it - before } }

    /** The middle of the answered tests, the mean of the two middle ones when there are an even number. */
    val medianMs: Int?
        get() = history.filterNotNull().sorted().takeIf { it.isNotEmpty() }?.let { (it[(it.size - 1) / 2] + it[it.size / 2]) / 2 }

    /** How far the answered tests stray from their mean, in ms; a node that ever timed out is the least steady. */
    val jitterMs: Double
        get() {
            if (history.any { it == null }) return Double.MAX_VALUE
            val values = history.filterNotNull()
            val mean = values.average()
            return sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
        }
}

/** The 節點 page: every node, the one in use, and when the [RV_NODES_TESTS] rounds of tests ran, oldest first. */
@Immutable
internal class RvNodesPageState(val nodes: List<RvNode>, val currentId: String, val testedAtEpochMs: List<Long>) {
    val places: Set<RvNodesPlace> get() = nodes.mapTo(mutableSetOf()) { it.place }

    /** The eight fastest nodes by their last test, or the eight steadiest over all tests. */
    fun top(steadiest: Boolean): List<RvNode> =
        if (steadiest) nodes.sortedBy { it.jitterMs }.take(TOP) else nodes.filter { it.latencyMs != null }.sortedBy { it.latencyMs }.take(TOP)

    /** The nodes by part of the world, fastest first in each; the part with the fastest node comes first. */
    fun byRegion(): List<Pair<RvNodesRegion, List<RvNode>>> = nodes
        .sortedWith(compareBy(nullsLast()) { it.latencyMs })
        .groupBy { it.place.region }
        .toList()
}

internal const val RV_NODES_TESTS = 8
private const val TOP = 8

/**
 * The sample: 48 nodes in 12 countries from two subscriptions. Each node's tests come from its own seed: a base for its
 * distance, its own spread, a drift up or down over the eight rounds and now and then a spike or a timeout, so no two
 * histories share a shape. Ids and short names count per country (sg-03, SG 03), names per city (Singapore 03,
 * Osaka 01). One node, sg-03, slows down steadily over the last rounds: the detail page's sample.
 */
internal fun sampleNodesPage(): RvNodesPageState {
    val perPlace = mutableMapOf<RvNodesPlace, Int>()
    val perCity = mutableMapOf<String, Int>()
    val nodes = SPECS.mapIndexed { i, spec ->
        val n = perPlace.merge(spec.place, 1, Int::plus)!!
        val number = two(n)
        val code = spec.place.name
        RvNode(
            id = "${code.lowercase()}-$number",
            name = "${spec.city} ${two(perCity.merge(spec.city, 1, Int::plus)!!)}${spec.suffix}",
            short = "$code $number",
            place = spec.place,
            protocol = spec.protocol.label,
            host = "${code.lowercase()}$n.example.net",
            port = spec.protocol.port,
            transport = spec.protocol.transport,
            tls = spec.protocol.tls,
            subscription = if (i % 3 == 2) SUBSCRIPTION_B else SUBSCRIPTION_A,
            history = SLOWING.takeIf { code == "SG" && n == 3 } ?: history(i, spec),
        )
    }
    val tested = List(RV_NODES_TESTS) { LAST_TEST - (RV_NODES_TESTS - 1 - it) * TEST_EVERY }
    return RvNodesPageState(nodes, currentId = "hk-02", testedAtEpochMs = tested)
}

private fun two(n: Int) = n.toString().padStart(2, '0')

/** The sample node the detail page shows. */
internal const val RV_NODES_DETAIL = "sg-03"

private fun history(index: Int, spec: Spec): List<Int?> {
    val random = Random(SEED + index * SEED_STEP)
    val drift = (random.nextFloat() - 0.5f) * spec.spread
    return List(RV_NODES_TESTS) { round ->
        val spike = if (random.nextFloat() < SPIKE_CHANCE) spec.spread * (2 + random.nextInt(3)) else 0
        val noise = random.nextGaussian() * spec.spread / 2
        val value = (spec.base + drift * round / RV_NODES_TESTS + noise + spike).roundToInt().coerceAtLeast(spec.base / 2)
        val timedOut = if (round == RV_NODES_TESTS - 1) index == TIMED_OUT else random.nextFloat() < TIMEOUT_CHANCE
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

/** A node before its numbers: where it is, its city name and an optional tag, its protocol, its base latency and spread. */
private class Spec(val place: RvNodesPlace, val city: String, val protocol: Proto, val base: Int, val spread: Int, val suffix: String = "")

private val SPECS = listOf(
    Spec(RvNodesPlace.HK, "Hong Kong", Proto.Trojan, 38, 10),
    Spec(RvNodesPlace.HK, "Hong Kong", Proto.Vless, 31, 6, " IPLC"),
    Spec(RvNodesPlace.HK, "Hong Kong", Proto.Ss, 44, 14),
    Spec(RvNodesPlace.HK, "Hong Kong", Proto.Hy2, 52, 22),
    Spec(RvNodesPlace.HK, "Hong Kong", Proto.TrojanWs, 41, 9, " IEPL"),
    Spec(RvNodesPlace.HK, "Hong Kong", Proto.Vmess, 63, 18),
    Spec(RvNodesPlace.TW, "Taipei", Proto.Trojan, 24, 5),
    Spec(RvNodesPlace.TW, "Taipei", Proto.Hy2, 29, 12),
    Spec(RvNodesPlace.TW, "Taichung", Proto.Ss, 35, 8),
    Spec(RvNodesPlace.TW, "Taipei", Proto.Vless, 27, 4),
    Spec(RvNodesPlace.TW, "Kaohsiung", Proto.Tuic, 39, 15),
    Spec(RvNodesPlace.JP, "Tokyo", Proto.Vless, 58, 9),
    Spec(RvNodesPlace.JP, "Tokyo", Proto.Trojan, 64, 16),
    Spec(RvNodesPlace.JP, "Osaka", Proto.Hy2, 71, 25),
    Spec(RvNodesPlace.JP, "Tokyo", Proto.Ss, 66, 11, " IPLC"),
    Spec(RvNodesPlace.JP, "Osaka", Proto.Vmess, 79, 13),
    Spec(RvNodesPlace.JP, "Tokyo", Proto.Tuic, 61, 20),
    Spec(RvNodesPlace.KR, "Seoul", Proto.Trojan, 74, 12),
    Spec(RvNodesPlace.KR, "Seoul", Proto.Vless, 82, 18),
    Spec(RvNodesPlace.KR, "Chuncheon", Proto.Ss, 91, 9),
    Spec(RvNodesPlace.SG, "Singapore", Proto.Trojan, 86, 14),
    Spec(RvNodesPlace.SG, "Singapore", Proto.Hy2, 97, 28),
    Spec(RvNodesPlace.SG, "Singapore", Proto.Vless, 92, 10),
    Spec(RvNodesPlace.SG, "Singapore", Proto.Ss, 104, 12),
    Spec(RvNodesPlace.SG, "Singapore", Proto.TrojanWs, 99, 17),
    Spec(RvNodesPlace.US, "Los Angeles", Proto.Vless, 152, 21),
    Spec(RvNodesPlace.US, "San Jose", Proto.Trojan, 161, 15),
    Spec(RvNodesPlace.US, "Seattle", Proto.Hy2, 174, 33),
    Spec(RvNodesPlace.US, "Los Angeles", Proto.Ss, 158, 12),
    Spec(RvNodesPlace.US, "New York", Proto.Vmess, 226, 24),
    Spec(RvNodesPlace.US, "Dallas", Proto.Tuic, 198, 19),
    Spec(RvNodesPlace.CA, "Vancouver", Proto.Trojan, 171, 14),
    Spec(RvNodesPlace.CA, "Toronto", Proto.Vless, 234, 27),
    Spec(RvNodesPlace.GB, "London", Proto.Trojan, 238, 18),
    Spec(RvNodesPlace.GB, "London", Proto.Hy2, 251, 40),
    Spec(RvNodesPlace.GB, "Manchester", Proto.Ss, 262, 15),
    Spec(RvNodesPlace.GB, "London", Proto.Vless, 244, 11),
    Spec(RvNodesPlace.DE, "Frankfurt", Proto.Vless, 221, 13),
    Spec(RvNodesPlace.DE, "Frankfurt", Proto.Trojan, 229, 22),
    Spec(RvNodesPlace.DE, "Berlin", Proto.Tuic, 247, 31),
    Spec(RvNodesPlace.NL, "Amsterdam", Proto.Trojan, 232, 16),
    Spec(RvNodesPlace.NL, "Amsterdam", Proto.Ss, 241, 10),
    Spec(RvNodesPlace.NL, "Rotterdam", Proto.Vmess, 268, 26),
    Spec(RvNodesPlace.FR, "Paris", Proto.Vless, 236, 14),
    Spec(RvNodesPlace.FR, "Marseille", Proto.Hy2, 214, 29),
    Spec(RvNodesPlace.AU, "Sydney", Proto.Trojan, 128, 17),
    Spec(RvNodesPlace.AU, "Melbourne", Proto.Vless, 141, 12),
    Spec(RvNodesPlace.AU, "Sydney", Proto.Ss, 136, 23),
)

/** sg-03's last rounds: fine at first, then slower each time. */
private val SLOWING = listOf(92, 88, 97, 104, 131, 158, 172, 186)

private const val SUBSCRIPTION_A = "Harbor Cloud"
private const val SUBSCRIPTION_B = "Lantern Net"

/** New York's last test timed out, so the list shows a node with no figure. */
private const val TIMED_OUT = 29

private const val SEED = 20_261_123L
private const val SEED_STEP = 7_919L
private const val SPIKE_CHANCE = 0.08f
private const val TIMEOUT_CHANCE = 0.03f

/** The last round ran at 2026-11-22 08:00 UTC, every half hour before it. */
private const val LAST_TEST = 1_795_334_400_000L
private const val TEST_EVERY = 1_800_000L
