package dev.doona.android.feature.stats

import dev.doona.android.designsystem.rv.RvPlace
import dev.doona.android.engine.Catalog
import dev.doona.android.engine.DayTotal
import dev.doona.android.engine.DayUsage
import dev.doona.android.engine.Group
import dev.doona.android.engine.GroupKind
import dev.doona.android.engine.Member
import dev.doona.android.engine.Mode
import dev.doona.android.engine.OutboundBytes
import dev.doona.android.engine.ScoreState
import dev.doona.android.engine.TodayTraffic
import dev.doona.android.engine.Usage
import dev.doona.android.engine.fake.rvSampleEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZoneOffset

class StatsMappingTest {
    private val engine = rvSampleEngine()

    private fun sampleInput(score: ScoreState? = null) = runBlocking {
        StatsInput(
            usage = engine.usage().getOrThrow(),
            today = engine.today.value,
            subscriptions = engine.subscriptions.value,
            catalog = engine.catalog.value,
            score = score,
            nowEpochMs = SAMPLE_NOW,
            zone = ZoneOffset.UTC,
        )
    }

    @Test
    fun `today comes from usage by the hour up to the current one`() {
        val input = sampleInput()
        val day = input.usage!!.today
        val state = rvStatsState(input)
        // 10:24: hours 0 to 10, the current one still filling.
        assertEquals(day.down.take(11), state.today.down)
        assertEquals(day.up.take(11), state.today.up)
        assertEquals(day.flows.take(11).map(Long::toInt), state.today.connections)
        assertEquals(input.today.yesterdayByNow, state.yesterdaySoFar)
        assertEquals(10.4f, state.nowHour, 1e-4f)
    }

    @Test
    fun `hours after the current one are left out of today's total`() {
        val hours = List(24) { it + 1L }
        val day = DayUsage("2026-11-22", hours, hours.map { it * 10 }, hours, emptyList())
        val state = rvStatsState(StatsInput(Usage(day, null, emptyList()), TodayTraffic(), emptyList(), null, null, SAMPLE_NOW, ZoneOffset.UTC))
        // Hours 0 to 10 hold 1..11 up and 10..110 down; 11 to 23 are later today and must not count.
        assertEquals(66L + 660L, state.today.total)
    }

    @Test
    fun `the time of day is local, so a half-hour zone moves the running total's end`() {
        val zone = ZoneId.of("Asia/Kolkata")
        val day = DayUsage("2026-11-22", List(24) { 0L }, List(24) { 0L }, List(24) { 0L }, emptyList())
        val state = rvStatsState(StatsInput(Usage(day, null, emptyList()), TodayTraffic(), emptyList(), null, null, SAMPLE_NOW, zone))
        // 10:24 UTC is 15:54 in Kolkata (UTC+05:30): hour 15, 54 minutes in, not 24.
        assertEquals(16, state.today.hours.size)
        assertEquals(15.9f, state.nowHour, 1e-4f)
    }

    @Test
    fun `a subscription without quota has no total and no expiry`() {
        val bare = sampleInput().let { input ->
            val subscriptions = input.subscriptions.map { it.copy(usedBytes = null, totalBytes = null, expiresAtEpochMs = null) }
            StatsInput(input.usage, input.today, subscriptions, input.catalog, null, SAMPLE_NOW, ZoneOffset.UTC)
        }
        val subscriptions = rvStatsState(bare).subscriptions
        // The quota card keeps only a total above zero, so no `0/0 B` legend; no expiry, so no date at the epoch (1970).
        assertTrue(subscriptions.all { it.totalBytes == 0L && it.usedBytes == 0L })
        assertTrue(subscriptions.all { it.expiresAtEpochMs == null })
    }

    @Test
    fun `the split is direct against the rest of the route targets`() {
        val day = sampleInput().usage!!.today
        val direct = day.outbounds.single { it.id == "direct" }.let { it.up + it.down }
        val split = rvStatsState(sampleInput()).split!!
        assertEquals(direct, split.directBytes)
        assertEquals(day.outbounds.sumOf { it.up + it.down } - direct, split.proxyBytes)
    }

    @Test
    fun `subscriptions keep their quota and the nodes their last test`() {
        val input = sampleInput()
        val state = rvStatsState(input)
        assertEquals(listOf(61_700_000_000L, 36_400_000_000L), state.subscriptions.map { it.usedBytes })
        val members = input.catalog!!.groups.flatMap { it.members }.filterNot { it.isGroup }.distinctBy { it.id }
        assertEquals(members.map { it.id }, state.nodes.map { it.id })
        assertEquals(members.map { it.latencyMs }, state.nodes.map { it.latencyMs })
        assertEquals(input.catalog.let { c -> c.groups.single { it.id == c.mainGroupId } }.selectedId, state.selectedId)
    }

    @Test
    fun `a fresh install with no usage is the empty state`() {
        val empty = DayUsage("2026-11-22", List(24) { 0L }, List(24) { 0L }, List(24) { 0L }, emptyList())
        val state = rvStatsState(StatsInput(Usage(empty, null, emptyList()), TodayTraffic(), emptyList(), null, null, SAMPLE_NOW, ZoneOffset.UTC))
        assertTrue(state.today.quiet)
        assertEquals(11, state.today.hours.size)
        assertNull(state.yesterdaySoFar)
        assertNull(state.split)
        assertNull(state.score)
        assertTrue(state.subscriptions.isEmpty())
        assertTrue(state.nodes.isEmpty())
    }

    @Test
    fun `before the first read and after midnight the day is empty, not yesterday's`() {
        val stale = sampleInput().let { StatsInput(it.usage, it.today, it.subscriptions, it.catalog, null, SAMPLE_NOW + DAY_MS, ZoneOffset.UTC) }
        assertTrue(rvStatsState(stale).today.quiet)
        assertNull(rvStatsState(stale).split)
        val unread = sampleInput().let { StatsInput(null, it.today, it.subscriptions, it.catalog, null, SAMPLE_NOW, ZoneOffset.UTC) }
        assertTrue(rvStatsState(unread).today.quiet)
    }

    @Test
    fun `the hours follow the local day`() {
        // 10:24 UTC is 21:24 in Melbourne (UTC+11), still on 2026-11-22.
        val input = sampleInput().let { StatsInput(it.usage, it.today, it.subscriptions, it.catalog, null, SAMPLE_NOW, ZoneOffset.ofHours(11)) }
        assertEquals(22, rvStatsState(input).today.hours.size)
    }

    @Test
    fun `the score card reads the score state with the groups' names and nodes`() {
        val score = runBlocking { engine.scoreState().getOrThrow() }
        val input = sampleInput(score)
        val state = rvStatsState(input).score!!
        assertSame(score, state.state)
        assertEquals(score.groups.map { it.id }, state.groups.map { it.id })
        val names = score.groups.map { g -> input.catalog!!.groups.single { it.id == g.id }.name }
        assertEquals(names, state.groups.map { it.name })
        assertEquals(score.groups.map { it.nodeIds }, state.groups.map { g -> g.members.map { it.id } })
    }

    @Test
    fun `no Score group means no Score card`() {
        assertNull(rvStatsState(sampleInput(ScoreState(0, emptyList()))).score)
    }

    @Test
    fun `the main node is followed through a selected subgroup`() {
        val node = Member("n1", "Tokyo 01")
        val sub = Group("g-JP", "JP", GroupKind.UrlTest, listOf(node), "n1")
        val main = Group("g-Proxy", "Proxy", GroupKind.Selector, listOf(Member("g-JP", "JP", isGroup = true)), "g-JP")
        val catalog = sampleInput().catalog!!.copy(mode = Mode.Rule, groups = listOf(main, sub), mainGroupId = "g-Proxy")
        val state = rvStatsState(StatsInput(null, TodayTraffic(), emptyList(), catalog, null, SAMPLE_NOW, ZoneOffset.UTC))
        assertEquals("n1", state.selectedId)
        assertEquals(listOf("n1"), state.nodes.map { it.id })
    }

    @Test
    fun `places come from a city, a flag or a country code, else other`() {
        assertEquals(RvPlace.JP, rvStatsPlace("Tokyo 02"))
        assertEquals(RvPlace.HK, rvStatsPlace("🇭🇰 香港 01"))
        assertEquals(RvPlace.US, rvStatsPlace("US-West 03"))
        assertEquals(RvPlace.Other, rvStatsPlace("節點 A"))
        assertEquals(RvPlace.Other, rvStatsPlace("Hourly"))
    }

    @Test
    fun `a failed test reads as no latency`() {
        val members = listOf(Member("a", "Tokyo 01", latencyMs = 40), Member("b", "Tokyo 02", failed = true), Member("c", "Tokyo 03"))
        val group = Group("g-Proxy", "Proxy", GroupKind.Selector, members, "a")
        val catalog: Catalog = sampleInput().catalog!!.copy(groups = listOf(group), mainGroupId = "g-Proxy")
        val nodes = rvStatsState(StatsInput(null, TodayTraffic(), emptyList(), catalog, null, SAMPLE_NOW, ZoneOffset.UTC)).nodes
        assertEquals(listOf(40, null, null), nodes.map { it.latencyMs })
        assertEquals(listOf(1, 0, 0), nodes.map { it.answered })
    }

    @Test
    fun `other route targets count as proxy`() {
        val day = DayUsage("2026-11-22", List(24) { 0L }, List(24) { 0L }, List(24) { 0L }, listOf(OutboundBytes("other", 1, 2), OutboundBytes("direct", 3, 4)))
        val split = rvStatsState(StatsInput(Usage(day, null, emptyList()), TodayTraffic(), emptyList(), null, null, SAMPLE_NOW, ZoneOffset.UTC)).split!!
        assertEquals(3L, split.proxyBytes)
        assertEquals(7L, split.directBytes)
    }

    @Test
    fun `a week from Monday is each day's bytes, then today's so far`() {
        val input = sampleInput().with(range = RvStatsRange.Week, firstDay = DayOfWeek.MONDAY)
        val period = rvStatsState(input).period!!
        val usage = input.usage!!
        val yesterday = usage.days.single { it.date == "2026-11-21" }
        assertEquals(7, period.slots)
        // 2026-11-22 is a Sunday: Monday the 16th to Saturday the 21st, then today.
        assertEquals(listOf(0L, 0L, 0L, 0L, 0L, yesterday.down, usage.today.down.take(11).sum()), period.days.down)
        assertEquals(listOf(0L, 0L, 0L, 0L, 0L, yesterday.up, usage.today.up.take(11).sum()), period.days.up)
        assertNull("past days keep no flows", period.days.connections)
        assertEquals(6 + DAY_GONE, period.now, 1e-4f)
    }

    @Test
    fun `a week from Sunday on a Sunday is today alone`() {
        val period = rvStatsState(sampleInput().with(range = RvStatsRange.Week, firstDay = DayOfWeek.SUNDAY)).period!!
        assertEquals(1, period.days.down.size)
        assertEquals(DAY_GONE, period.now, 1e-4f)
    }

    @Test
    fun `a month runs from the 1st over the month's days and has nothing to compare`() {
        val period = rvStatsState(sampleInput().with(range = RvStatsRange.Month)).period!!
        assertEquals(30, period.slots)
        assertEquals(22, period.days.down.size)
        assertNull(period.before)
        assertNull(RvStatsRange.Month.against)
    }

    @Test
    fun `today has no period`() {
        assertNull(rvStatsState(sampleInput()).period)
    }

    @Test
    fun `the week before is compared by the same point, its same weekday by the share of today gone`() {
        val base = sampleInput()
        val usage = base.usage!!.copy(days = listOf(DayTotal("2026-11-10", 200, 300), DayTotal("2026-11-15", 400, 600), DayTotal("2026-11-08", 9_999, 9_999)))
        val period = rvStatsState(base.with(usage = usage, range = RvStatsRange.Week, firstDay = DayOfWeek.MONDAY)).period!!
        // Tuesday the 10th whole, Sunday the 15th (today's weekday) by 10:24 of 24 h; the 8th is before that week.
        assertEquals(500L + (1_000L * DAY_GONE).toLong(), period.before)
    }

    @Test
    fun `yesterday missing from the kept days comes from usage's yesterday`() {
        val base = sampleInput()
        val usage = base.usage!!.copy(days = emptyList())
        val period = rvStatsState(base.with(usage = usage, range = RvStatsRange.Week, firstDay = DayOfWeek.MONDAY)).period!!
        assertEquals(usage.yesterday!!.down.sum(), period.days.down[5])
    }

    @Test
    fun `a picked subscription narrows the quota and the nodes, not the traffic or the tools`() {
        val base = sampleInput()
        val picked = base.subscriptions.first().id
        val all = rvStatsState(base)
        val state = rvStatsState(base.with(subscriptionId = picked))
        assertEquals(picked, state.subscriptionId)
        assertEquals(listOf(picked), state.shownSubscriptions.map { it.id })
        assertTrue(state.shownNodes.isNotEmpty())
        assertTrue(state.shownNodes.all { it.subscription == picked })
        assertTrue(state.shownNodes.size < state.nodes.size)
        assertEquals(all.nodes, state.nodes)
        assertEquals(all.today, state.today)
        assertEquals(all.split, state.split)
    }

    @Test
    fun `a subscription deleted since it was picked leaves the chip at all of them`() {
        val state = rvStatsState(sampleInput().with(subscriptionId = "gone"))
        assertNull(state.subscriptionId)
        assertEquals(state.subscriptions, state.shownSubscriptions)
    }

    private fun StatsInput.with(
        usage: Usage? = this.usage,
        range: RvStatsRange = this.range,
        subscriptionId: String? = this.subscriptionId,
        firstDay: DayOfWeek = this.firstDay,
    ) = StatsInput(usage, today, subscriptions, catalog, score, nowEpochMs, zone, range, subscriptionId, firstDay)

    private companion object {
        /** RvSampleTraffic's snapshot: 2026-11-22 10:24 UTC. */
        const val SAMPLE_NOW = 1_795_343_040_000L
        const val DAY_MS = 86_400_000L

        /** The share of the sample's day gone at 10:24. */
        const val DAY_GONE = (10 * 60 + 24) / (24 * 60f)
    }
}
