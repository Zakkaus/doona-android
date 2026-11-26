package dev.doona.android.feature.stats

import dev.doona.android.feature.activity.HonkProfile
import dev.doona.android.feature.activity.readHonkProfile
import dev.doona.android.vpn.ActivityHonkFixtures
import dev.doona.android.vpn.ActivityHonkFixtures.scoring
import dev.doona.android.vpn.FakeHonk
import dev.doona.android.vpn.honk.NativeCatalog
import dev.doona.android.vpn.honk.NativeRouting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZoneOffset

/** 統計's mapping over the RV sample as honk serves it ([ActivityHonkFixtures.sample]); the sample's now is Sunday 10:24 UTC. */
class StatsMappingTest {
    private val profile = profile(ActivityHonkFixtures.sample())

    private fun profile(honk: FakeHonk) = readHonkProfile(NativeCatalog(honk), NativeRouting(honk))

    private fun input(
        profile: HonkProfile? = this.profile,
        zone: ZoneId = ZoneOffset.UTC,
        range: RvStatsRange = RvStatsRange.Today,
        subscriptionId: String? = null,
        firstDay: DayOfWeek = DayOfWeek.MONDAY,
    ) = StatsInput(profile, SAMPLE_NOW, zone, range, subscriptionId, firstDay)

    @Test
    fun `the sample's providers, nodes and main node, over a day with no bytes`() {
        val state = rvStatsState(input())
        assertEquals(listOf("Harbor Cloud", "Lantern Net"), state.subscriptions.map { it.name })
        assertTrue(state.subscriptions.all { it.totalBytes > 0 })
        assertEquals(48, state.nodes.size)
        assertEquals("n-0001938f00000000", state.selectedId)
        // honk keeps no usage by the day: hours 0 to 10 so far, all zero, and nothing from yesterday or for the split.
        assertEquals(11, state.today.hours.size)
        assertTrue(state.today.quiet)
        assertNull(state.yesterdaySoFar)
        assertNull(state.split)
        assertNull(state.score)
        assertNull(state.period)
    }

    @Test
    fun `the time of day is local, so a half-hour zone moves the running total's end`() {
        val state = rvStatsState(input(zone = ZoneId.of("Asia/Kolkata")))
        // 10:24 UTC is 15:54 in Kolkata (UTC+05:30): hour 15, 54 minutes in, not 24.
        assertEquals(16, state.today.hours.size)
        assertEquals(15.9f, state.nowHour, 1e-4f)
    }

    @Test
    fun `before the first read there are no nodes, no subscriptions and no main node`() {
        val state = rvStatsState(input(profile = null))
        assertTrue(state.nodes.isEmpty())
        assertTrue(state.subscriptions.isEmpty())
        assertNull(state.selectedId)
    }

    @Test
    fun `a profile with no main group lists its nodes and selects none`() {
        val state = rvStatsState(input(profile = profile.run { HonkProfile(providers, nodes, groups, mainGroup = null, ruleCount) }))
        assertEquals(48, state.nodes.size)
        assertNull(state.selectedId)
    }

    @Test
    fun `a Score group's card reads what it chose per network, with no counters`() {
        val score = rvStatsState(input(profile = profile(ActivityHonkFixtures.sample().scoring()))).score!!
        assertEquals(0L, score.starts + score.trialsOk + score.trialsFailed)
        val group = score.groups.single()
        assertEquals("HK", group.name)
        assertEquals("n-0001938f00000000", group.tcp.selectedId)
        assertEquals("n-0001938e00000000", group.udp.selectedId)
        assertTrue(group.tcp.settled)
        assertEquals(6, group.tcp.candidates)
        assertEquals(48, group.members.size)
    }

    @Test
    fun `a week from Monday is a day each up to today, with nothing to compare`() {
        val period = rvStatsState(input(range = RvStatsRange.Week)).period!!
        assertEquals(DAYS_PER_WEEK, period.slots)
        assertEquals(DAYS_PER_WEEK, period.days.hours.size)
        assertEquals(6 + DAY_GONE, period.now, 1e-4f)
        assertNull(period.before)
    }

    @Test
    fun `a week from Sunday on a Sunday is today alone`() {
        val period = rvStatsState(input(range = RvStatsRange.Week, firstDay = DayOfWeek.SUNDAY)).period!!
        assertEquals(1, period.days.hours.size)
        assertEquals(DAY_GONE, period.now, 1e-4f)
    }

    @Test
    fun `a month runs from the 1st over the month's days`() {
        val period = rvStatsState(input(range = RvStatsRange.Month)).period!!
        assertEquals(30, period.slots)
        assertEquals(22, period.days.hours.size)
        assertNull(period.before)
    }

    @Test
    fun `a picked subscription narrows the quota and the nodes to that provider's`() {
        val all = rvStatsState(input())
        val state = rvStatsState(input(subscriptionId = "s2"))
        assertEquals("s2", state.subscriptionId)
        assertEquals(listOf("s2"), state.shownSubscriptions.map { it.id })
        assertEquals(16, state.shownNodes.size)
        assertTrue(state.shownNodes.all { it.subscriptionId == "s2" })
        assertEquals(all.nodes, state.nodes)
    }

    @Test
    fun `a subscription deleted since it was picked leaves the chip at all of them`() {
        val state = rvStatsState(input(subscriptionId = "gone"))
        assertNull(state.subscriptionId)
        assertEquals(state.subscriptions, state.shownSubscriptions)
    }

    private companion object {
        /** The share of the sample's day gone at 10:24. */
        const val DAY_GONE = (10 * 60 + 24) / (24 * 60f)
    }
}
