package dev.doona.android.feature.stats

import dev.doona.android.designsystem.rv.RvPlace
import dev.doona.android.engine.Catalog
import dev.doona.android.engine.DayTotal
import dev.doona.android.engine.DayUsage
import dev.doona.android.engine.Member
import dev.doona.android.engine.ScoreState
import dev.doona.android.engine.Subscription
import dev.doona.android.engine.TodayTraffic
import dev.doona.android.engine.Usage
import dev.doona.android.feature.leafOf
import dev.doona.android.feature.model.RvCity
import dev.doona.android.feature.model.RvDay
import dev.doona.android.feature.model.RvNode
import dev.doona.android.feature.model.RvSplit
import dev.doona.android.feature.model.RvSubscription
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * Everything the 統計 page reads from the engine at one instant, and what its chips picked; [usage] is null until the
 * first read answers. A week starts on [firstDay], the locale's.
 */
internal class StatsInput(
    val usage: Usage?,
    val today: TodayTraffic,
    val subscriptions: List<Subscription>,
    val catalog: Catalog?,
    val score: ScoreState?,
    val nowEpochMs: Long,
    val zone: ZoneId,
    val range: RvStatsRange = RvStatsRange.Today,
    val subscriptionId: String? = null,
    val firstDay: DayOfWeek = DayOfWeek.MONDAY,
)

/**
 * The engine's reads as the 統計 page's model. Pure: the same input gives the same page. [placeOf] tells where a node is
 * from its name.
 */
internal fun rvStatsState(input: StatsInput, placeOf: (String) -> RvPlace = ::rvStatsPlace): RvStatsState {
    val nodes = input.catalog?.leafNodes(placeOf).orEmpty()
    val day = input.usage?.today?.takeIf { it.date == localDate(input) }
    val today = rvDay(day, hoursSoFar(input))
    return RvStatsState(
        range = input.range,
        today = today,
        yesterdaySoFar = input.today.yesterdayByNow,
        subscriptions = input.subscriptions.map(::rvSubscription),
        nodes = nodes,
        selectedId = input.catalog?.mainNodeId(),
        split = day?.let(::rvSplit),
        nowEpochMs = input.nowEpochMs,
        nowHour = Instant.ofEpochMilli(input.nowEpochMs).atZone(input.zone).toLocalTime().toSecondOfDay().toFloat() / SECONDS_PER_HOUR,
        score = input.score?.let { rvStatsScore(it, input.catalog, nodes) },
        period = rvPeriod(input, today),
        // A subscription deleted since it was picked leaves the chip at all of them.
        subscriptionId = input.subscriptionId?.takeIf { id -> input.subscriptions.any { it.id == id } },
    )
}

/**
 * 本週 (from the locale's first day) or 本月 (from the 1st) by the day: each past day's bytes from `usage().days` (a day
 * not listed had none; yesterday falls back to `usage().yesterday`), then today's so far. The week before is compared by
 * the same point: its days up to the same weekday whole, and that weekday by the share of today gone, since past days
 * keep no hours. A month has nothing to compare: the 31 days kept do not reach back over the month before.
 */
private fun rvPeriod(input: StatsInput, today: RvDay): RvStatsPeriod? {
    val now = Instant.ofEpochMilli(input.nowEpochMs).atZone(input.zone)
    val date = now.toLocalDate()
    val (first, slots) = when (input.range) {
        RvStatsRange.Today -> return null
        RvStatsRange.Week -> date.with(TemporalAdjusters.previousOrSame(input.firstDay)) to DAYS_PER_WEEK
        RvStatsRange.Month -> date.withDayOfMonth(1) to date.lengthOfMonth()
    }
    val kept = input.usage?.days.orEmpty().associateBy { it.date }
    val yesterday = input.usage?.yesterday?.let { DayTotal(it.date, it.up.sum(), it.down.sum()) }
    fun on(day: LocalDate): DayTotal? = kept[day.toString()] ?: yesterday?.takeIf { it.date == day.toString() }
    val past = generateSequence(first) { it.plusDays(1) }.takeWhile { it < date }.toList()
    val days = RvDay(past.map { on(it)?.down ?: 0L } + today.downTotal, past.map { on(it)?.up ?: 0L } + today.upTotal, null)
    val dayGone = now.toLocalTime().toSecondOfDay().toFloat() / SECONDS_PER_DAY
    val before = if (input.range == RvStatsRange.Week) {
        val sameDay = date.minusDays(DAYS_PER_WEEK.toLong())
        val whole = past.sumOf { on(it.minusDays(DAYS_PER_WEEK.toLong()))?.total ?: 0L }
        whole + ((on(sameDay)?.total ?: 0L) * dayGone).toLong()
    } else {
        null
    }
    return RvStatsPeriod(days, slots, past.size + dayGone, before)
}

private val DayTotal.total get() = up + down

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
 * card leaves out; a subscription never fetched has no update time, which the tools row skips.
 */
private fun rvSubscription(sub: Subscription) = RvSubscription(
    id = sub.id,
    name = sub.name,
    nodes = sub.nodes,
    updatedAtEpochMs = sub.lastFetch?.atEpochMs,
    usedBytes = sub.usedBytes ?: 0L,
    totalBytes = sub.totalBytes ?: 0L,
    expiresAtEpochMs = sub.expiresAtEpochMs,
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
    subscriptionId = member.subscriptionId,
    history = when {
        member.latencyMs != null -> listOf(member.latencyMs)
        member.failed -> listOf(null)
        else -> emptyList()
    },
)

/**
 * The node the main group sends through, followed through any subgroup it selected ([leafOf]); null with no main group
 * (a hand-edited profile may have none) or a selection that ends in nothing.
 */
private fun Catalog.mainNodeId(): String? {
    val byId = groups.associateBy { it.id }
    val main = byId[mainGroupId] ?: return null
    return main.members.firstOrNull { it.id == main.selectedId }?.let { leafOf(it, byId) }?.id
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
internal const val DAYS_PER_WEEK = 7
private const val SECONDS_PER_DAY = 86_400
private const val SECONDS_PER_HOUR = 3_600
private const val REGIONAL_A = 0x1F1E6
private const val REGIONAL_Z = 0x1F1FF
