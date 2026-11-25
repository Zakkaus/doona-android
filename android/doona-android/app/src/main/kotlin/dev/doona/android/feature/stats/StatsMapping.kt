package dev.doona.android.feature.stats

import dev.doona.android.designsystem.rv.RvPlace
import dev.doona.android.engine.Catalog
import dev.doona.android.engine.DayUsage
import dev.doona.android.engine.Member
import dev.doona.android.engine.ScoreState
import dev.doona.android.engine.Subscription
import dev.doona.android.engine.TodayTraffic
import dev.doona.android.engine.Usage
import dev.doona.android.feature.model.RvCity
import dev.doona.android.feature.model.RvDay
import dev.doona.android.feature.model.RvNode
import dev.doona.android.feature.model.RvSplit
import dev.doona.android.feature.model.RvSubscription
import java.net.URI
import java.time.Instant
import java.time.ZoneId

/** Everything the 統計 page reads from the engine at one instant; [usage] is null until the first read answers. */
internal class StatsInput(
    val usage: Usage?,
    val today: TodayTraffic,
    val subscriptions: List<Subscription>,
    val catalog: Catalog?,
    val score: ScoreState?,
    val nowEpochMs: Long,
    val zone: ZoneId,
)

/**
 * The engine's reads as the 統計 page's model. Pure: the same input gives the same page. [placeOf] tells where a node is
 * from its name.
 */
internal fun rvStatsState(input: StatsInput, placeOf: (String) -> RvPlace = ::rvStatsPlace): RvStatsState {
    val nodes = input.catalog?.leafNodes(placeOf).orEmpty()
    val day = input.usage?.today?.takeIf { it.date == localDate(input) }
    return RvStatsState(
        range = RvStatsRange.Today,
        today = rvDay(day, hoursSoFar(input)),
        yesterdaySoFar = input.today.yesterdayByNow,
        subscriptions = input.subscriptions.map(::rvSubscription),
        nodes = nodes,
        selectedId = input.catalog?.mainNodeId(),
        split = day?.let(::rvSplit),
        nowEpochMs = input.nowEpochMs,
        score = input.score?.let { rvStatsScore(it, input.catalog, nodes) },
    )
}

/** Today's hours from midnight up to the current one, which is still filling; a day not yet read is all zero. */
private fun rvDay(day: DayUsage?, hours: Int): RvDay {
    val zeros = List(hours) { 0L }
    if (day == null) return RvDay(zeros, zeros, zeros.map(Long::toInt))
    fun List<Long>.toNow() = List(hours) { getOrElse(it) { 0L } }
    return RvDay(day.down.toNow(), day.up.toNow(), day.flows.toNow().map { it.coerceAtMost(Int.MAX_VALUE.toLong()).toInt() })
}

/** The hours of the local day so far, the current one included. */
private fun hoursSoFar(input: StatsInput): Int = Instant.ofEpochMilli(input.nowEpochMs).atZone(input.zone).hour + 1

private fun localDate(input: StatsInput): String = Instant.ofEpochMilli(input.nowEpochMs).atZone(input.zone).toLocalDate().toString()

/**
 * Bytes that left directly (route target `direct`) against the rest, which went through a proxy; `other` (route targets
 * past the day's 64) counts as proxy. Null before the day's first byte, so the card shows its empty state.
 */
private fun rvSplit(day: DayUsage): RvSplit? {
    val total = day.outbounds.sumOf { it.up + it.down }
    if (total <= 0L) return null
    val direct = day.outbounds.filter { it.id == DIRECT }.sumOf { it.up + it.down }
    return RvSplit(total - direct, direct)
}

/**
 * A subscription as the quota card and the tools row read it. A provider that sent no quota is a total of 0, which the
 * card leaves out; a subscription never fetched has no update time, 0 here, which the tools row skips.
 */
private fun rvSubscription(sub: Subscription) = RvSubscription(
    id = sub.id,
    name = sub.name,
    nodes = sub.nodes,
    updatedAtEpochMs = sub.updatedAtEpochMs ?: 0L,
    usedBytes = sub.usedBytes ?: 0L,
    totalBytes = sub.totalBytes ?: 0L,
    expiresAtEpochMs = sub.expiresAtEpochMs,
    host = runCatching { URI(sub.url).host }.getOrNull().orEmpty(),
)

/** Every node of every group once, in catalog order; subgroups are left out. */
private fun Catalog.leafNodes(placeOf: (String) -> RvPlace): List<RvNode> =
    groups.asSequence().flatMap { it.members }.filterNot { it.isGroup }.distinctBy { it.id }.map { rvNode(it, placeOf) }.toList()

/**
 * A member as 節點分布 and 延遲分布 read it: its name, where it is, and its last test (a failed test as a timeout). The
 * endpoint fields stay empty: the page shows none of them.
 */
private fun rvNode(member: Member, placeOf: (String) -> RvPlace) = RvNode(
    id = member.id,
    name = member.name,
    short = member.name,
    place = placeOf(member.name),
    city = null,
    protocol = "",
    host = "",
    port = 0,
    transport = "",
    tls = false,
    subscription = member.subscriptionId.orEmpty(),
    history = when {
        member.latencyMs != null -> listOf(member.latencyMs)
        member.failed -> listOf(null)
        else -> emptyList()
    },
)

/** The node the main group sends through, followed through any subgroup it selected. */
private fun Catalog.mainNodeId(): String? {
    val byId = groups.associateBy { it.id }
    var id = byId[mainGroupId]?.selectedId
    repeat(byId.size) {
        val group = byId[id] ?: return id
        id = group.selectedId
    }
    return id
}

/** The Score card's groups: each with the catalog's name and its nodes; null when no group uses Score. */
private fun rvStatsScore(state: ScoreState, catalog: Catalog?, nodes: List<RvNode>): RvStatsScore? {
    if (state.groups.isEmpty()) return null
    val byId = nodes.associateBy { it.id }
    val groups = state.groups.map { group ->
        RvStatsScoreGroup(group.id, catalog?.groups?.firstOrNull { it.id == group.id }?.name ?: group.id, group.nodeIds.mapNotNull(byId::get))
    }
    return RvStatsScore(state, groups)
}

/**
 * Where a node is, from its name: a city (`Tokyo 02`), a flag (`🇯🇵 02`) or a country code as a word (`JP-02`, `HK 01`);
 * anything else is [RvPlace.Other]. A stand-in until the shared place mapper of the nodes pages exists.
 */
internal fun rvStatsPlace(name: String): RvPlace {
    RvCity.entries.firstOrNull { name.contains(it.en, ignoreCase = true) }?.let { return it.place }
    flagCode(name)?.let { code -> RvPlace.entries.firstOrNull { it.name == code }?.let { return it } }
    val words = name.split(Regex("[^A-Za-z]+")).toSet()
    return RvPlace.entries.firstOrNull { it != RvPlace.Other && it.name in words } ?: RvPlace.Other
}

/** The two letters of the first flag emoji in [name] (two regional indicator symbols), or null. */
private fun flagCode(name: String): String? {
    val points = name.codePoints().toArray()
    for (i in 0 until points.size - 1) {
        val (a, b) = points[i] to points[i + 1]
        if (a in REGIONAL_A..REGIONAL_Z && b in REGIONAL_A..REGIONAL_Z) return "${'A' + (a - REGIONAL_A)}${'A' + (b - REGIONAL_A)}"
    }
    return null
}

private const val DIRECT = "direct"
private const val REGIONAL_A = 0x1F1E6
private const val REGIONAL_Z = 0x1F1FF
