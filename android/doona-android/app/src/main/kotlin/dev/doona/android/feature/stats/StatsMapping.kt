package dev.doona.android.feature.stats

import dev.doona.android.feature.activity.HonkProfile
import dev.doona.android.feature.activity.honkActivityNodes
import dev.doona.android.feature.model.CoreNodePlaces
import dev.doona.android.feature.model.NodePlaces
import dev.doona.android.feature.model.RvNode
import dev.doona.android.feature.model.RvScorePick
import dev.doona.android.feature.model.RvSplit
import dev.doona.android.feature.subscriptions.rvSubscription
import dev.doona.android.honk.Group
import dev.doona.android.honk.GroupSelection
import dev.doona.android.honk.HealthState
import dev.doona.android.honk.OutboundKind
import dev.doona.android.honk.OutboundTraffic
import dev.doona.android.honk.PolicyKind
import dev.doona.android.honk.ProviderKind
import dev.doona.android.honk.Transport
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * What the 統計 page reads from honk at one instant, and what its chips picked; [profile] is null until the first read
 * answers, and [outbounds] (the bytes per outbound since honk started) until its first read does. A week starts on [firstDay], the locale's.
 */
internal class StatsInput(
    val profile: HonkProfile?,
    val nowEpochMs: Long,
    val zone: ZoneId,
    val range: RvStatsRange = RvStatsRange.Today,
    val subscriptionId: String? = null,
    val firstDay: DayOfWeek = DayOfWeek.MONDAY,
    val outbounds: List<OutboundTraffic>? = null,
)

/**
 * honk's profile as the 統計 page's model. Pure: the same input gives the same page. [places] tells where a node is from
 * its name. honk keeps no usage by the day, so the traffic cards show their empty state (q-usage, q-direct-proxy).
 */
internal fun rvStatsState(input: StatsInput, places: NodePlaces = CoreNodePlaces): RvStatsState {
    val profile = input.profile
    val nodes = honkActivityNodes(profile, places).nodes
    val providers = profile?.providers.orEmpty().filter { it.kind == ProviderKind.Subscription }
    val now = Instant.ofEpochMilli(input.nowEpochMs).atZone(input.zone)
    val main = profile?.groups?.firstOrNull { it.name == profile.mainGroup }
    return RvStatsState(
        range = input.range,
        today = null, // honk-gap:daily-usage
        yesterdaySoFar = null,
        subscriptions = providers.map { rvSubscription(it, userAgent = null) },
        nodes = nodes,
        selectedId = main?.runtime?.selection?.tcp?.resolvedLeafNodeId,
        split = input.outbounds?.let(::rvSplit),
        nowEpochMs = input.nowEpochMs,
        nowHour = now.toLocalTime().toSecondOfDay().toFloat() / SECONDS_PER_HOUR,
        score = rvStatsScore(profile?.groups.orEmpty(), nodes),
        period = rvPeriod(input),
        // A subscription deleted since it was picked leaves the chip at all of them.
        subscriptionId = input.subscriptionId?.takeIf { id -> providers.any { it.id == id } },
    )
}

/**
 * 分流比例 since honk started (labelled 本次啟動: owner, 2026-11-26, q-direct-proxy): `direct` against every group and
 * node, as honk files each connection once under the outbound its rules picked; `block` carries nothing. Null before
 * any byte, so the card shows its empty state.
 */
private fun rvSplit(outbounds: List<OutboundTraffic>): RvSplit? {
    val bytes = { outbound: OutboundTraffic -> outbound.up + outbound.down }
    val direct = outbounds.filter { it.kind == OutboundKind.Builtin && it.name == DIRECT }.sumOf(bytes)
    val proxy = outbounds.filter { it.kind != OutboundKind.Builtin }.sumOf(bytes)
    return RvSplit(proxyBytes = proxy, directBytes = direct).takeIf { proxy + direct > 0 }
}

/**
 * The local calendar bounds of 本週 or 本月, even without daily usage.
 */
private fun rvPeriod(input: StatsInput): RvStatsPeriod? {
    val now = Instant.ofEpochMilli(input.nowEpochMs).atZone(input.zone)
    val date = now.toLocalDate()
    val (first, slots) = when (input.range) {
        RvStatsRange.Today -> return null
        RvStatsRange.Week -> date.with(TemporalAdjusters.previousOrSame(input.firstDay)) to DAYS_PER_WEEK
        RvStatsRange.Month -> date.withDayOfMonth(1) to date.lengthOfMonth()
    }
    val past = generateSequence(first) { it.plusDays(1) }.takeWhile { it < date }.count()
    // honk-gap:daily-usage
    return RvStatsPeriod(null, slots, past + now.toLocalTime().toSecondOfDay().toFloat() / SECONDS_PER_DAY, before = null)
}

/**
 * The Score card: each of honk's Score groups with what it chose per network, its nodes among [nodes]; null when no
 * group uses Score. honk reports no Score counters (connections started, trials), so those are zero (q-score).
 */
private fun rvStatsScore(groups: List<Group>, nodes: List<RvNode>): RvStatsScore? {
    val score = groups.filter { it.policy.kind == PolicyKind.Score }.takeIf { it.isNotEmpty() } ?: return null
    return RvStatsScore(
        // honk-gap:score-evidence
        starts = null,
        trialsOk = null,
        trialsFailed = null,
        groups = score.map { RvStatsScoreGroup(it.id, it.name, nodes, scorePick(it, Transport.Tcp), scorePick(it, Transport.Udp)) },
    )
}

/**
 * What [group] chose on [transport]: the member its selection names, followed to the node it ends on, and how many of its
 * members honk has a probe of on that transport, out of all of them. honk's group shows its choice and each member's
 * health; it names no wait and no challenger.
 */
private fun scorePick(group: Group, transport: Transport): RvScorePick {
    val selection: GroupSelection? = if (transport == Transport.Tcp) group.runtime.selection.tcp else group.runtime.selection.udp
    val probed = group.runtime.health.filter { it.transport == transport && it.state != HealthState.Unknown }.map { it.memberId }.toSet()
    return RvScorePick(
        selectedId = selection?.resolvedLeafNodeId,
        selectedName = selection?.let { s -> group.members.firstOrNull { it.id == s.memberId }?.name },
        settled = null, // honk-gap:score-evidence
        wait = null,
        compared = group.members.count { it.id in probed },
        candidates = group.members.size,
        challengers = emptyList(),
    )
}

private const val DIRECT = "direct"

internal const val DAYS_PER_WEEK = 7
private const val SECONDS_PER_DAY = 86_400
private const val SECONDS_PER_HOUR = 3_600
