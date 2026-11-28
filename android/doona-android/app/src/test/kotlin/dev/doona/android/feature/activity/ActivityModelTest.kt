package dev.doona.android.feature.activity

import dev.doona.android.designsystem.rv.RvPlace
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.FailureReason
import dev.doona.android.engine.Mode
import dev.doona.android.engine.Status
import dev.doona.android.feature.model.CoreNodePlaces
import dev.doona.android.feature.model.RvGroupKind
import dev.doona.android.feature.model.RvRouteMode
import dev.doona.android.feature.model.protocolName
import dev.doona.android.honk.ActivityHonkFixtures
import dev.doona.android.honk.ActivityHonkFixtures.allUnavailable
import dev.doona.android.honk.FakeHonk
import dev.doona.android.honk.Group
import dev.doona.android.honk.GroupRuntime
import dev.doona.android.honk.GroupSelection
import dev.doona.android.honk.GroupSelections
import dev.doona.android.honk.NativeCatalog
import dev.doona.android.honk.NativeRouting
import dev.doona.android.honk.NativeRuntime
import dev.doona.android.honk.RuntimeTraffic
import dev.doona.android.honk.TrafficHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 活動's mapping over the RV sample as honk serves it ([ActivityHonkFixtures.sample]). */
class ActivityModelTest {
    private val honk = ActivityHonkFixtures.sample()
    private val profile = profile(honk)
    private val outbounds = NativeRuntime(honk).outbounds()

    private fun profile(honk: FakeHonk) = readHonkProfile(NativeCatalog(honk), NativeRouting(honk))

    private fun data(profile: HonkProfile? = this.profile, mode: Mode = Mode.Rule) = ActivityData(profile, mode, outbounds, nowEpochMs = 1_000, nowHour = 13.5f)

    private fun map(data: ActivityData) = activityCatalog(data, honkActivityNodes(data.profile, CoreNodePlaces))

    private fun HonkProfile.with(groups: List<Group> = this.groups, mainGroup: String? = this.mainGroup) = HonkProfile(providers, nodes, groups, mainGroup, ruleCount)

    @Test
    fun `the main node is what the group the rules fall back to selects over TCP`() {
        val mapped = map(data())
        assertEquals(RvMainNode("n-0001938f00000000", "Hong Kong 02 IPLC", 32, false), mapped.node)
        assertEquals("n-0001938f00000000", mapped.cards.mainNodeId)
        assertEquals(RvRouteMode.Rule, mapped.mode)
    }

    @Test
    fun `rules that fall back to direct leave no main node, and the main group's bytes get a row`() {
        val mapped = map(data(profile.with(mainGroup = null)))
        assertNull(mapped.node)
        assertEquals("", mapped.cards.mainNodeId)
        assertEquals(listOf("Streaming", null, "Proxy", "AI"), mapped.rules.map { it.label })
    }

    @Test
    fun `direct uses no node, while the cards still know the main group's choice`() {
        val mapped = map(data(mode = Mode.Direct))
        assertNull(mapped.node)
        assertEquals(RvRouteMode.Direct, mapped.mode)
        assertEquals("n-0001938f00000000", mapped.cards.mainNodeId)
    }

    @Test
    fun `before the profile is read nothing claims there is no subscription`() {
        val mapped = map(data(profile = null, mode = Mode.Global))
        assertEquals(RvRouteMode.Global, mapped.mode)
        assertNull(mapped.node)
        assertTrue(mapped.hasSubscriptions)
        assertTrue(mapped.rules.isEmpty())
    }

    @Test
    fun `rule rows follow honk's bytes per group and direct, the most first, without the main group and block, and carry no bytes`() {
        assertEquals(
            listOf(
                RvActivityRule("Streaming", "g-Streaming", "n-0001c21100000000", null, null),
                RvActivityRule(null, null, null, null, null),
                RvActivityRule("AI", "g-AI", "n-00019bac00000000", null, null),
            ),
            map(data()).rules,
        )
    }

    @Test
    fun `a group whose UDP selection ends on another node names it as its second node`() {
        val split = profile.groups.map { group ->
            if (group.id != "g-Streaming") return@map group
            val tcp = group.runtime.selection.tcp
            val udp = GroupSelection("n-0001c55300000000", "n-0001c55300000000", "user")
            Group(group.id, group.name, group.icon, group.policy, group.members, GroupRuntime(GroupSelections(tcp, udp), group.runtime.health), group.capabilities)
        }
        val rule = map(data(profile.with(groups = split))).rules.single { it.groupId == "g-Streaming" }
        assertEquals("n-0001c21100000000", rule.nodeId)
        assertEquals("n-0001c55300000000", rule.udpNodeId)
    }

    @Test
    fun `a rule row never repeats its title as its subtitle`() {
        val direct = map(data()).rules.single { it.label == null }
        // A rule that sends to 直連 ends on no node: its line would be 直連 again, so it has none.
        assertEquals(RuleRowText("直連", null), ruleRowText(direct.label, "直連", "直連"))
        assertEquals(RuleRowText("Streaming", "Taipei 01"), ruleRowText("Streaming", "Taipei 01", "直連"))
        // A group named as its node: the name once.
        assertEquals(RuleRowText("Tokyo 02", null), ruleRowText("Tokyo 02", "Tokyo 02", "直連"))
    }

    @Test
    fun `a node's place is read from its name`() {
        val nodes = honkActivityNodes(profile, CoreNodePlaces).byId
        assertEquals(RvPlace.HK, nodes.getValue("n-0001938f00000000").place)
        assertEquals(RvPlace.TW, nodes.getValue("n-0001c21100000000").place)
        assertEquals(RvPlace.JP, nodes.getValue("n-00019bac00000000").place)
    }

    @Test
    fun `a rule row whose TCP and UDP nodes are in different places leads with its group's glyph`() {
        val nodes = honkActivityNodes(profile, CoreNodePlaces).byId
        val node = nodes.getValue("n-0001c21100000000")
        val udp = nodes.getValue("n-00019bac00000000")
        assertEquals(RuleLead.GroupGlyph, ruleLead(node, udp))
        // In one place, or with no place for either, the one flag stands for both.
        assertEquals(RuleLead.Flag, ruleLead(node, udp.copy(place = RvPlace.TW)))
        assertEquals(RuleLead.Flag, ruleLead(node.copy(place = RvPlace.Other), udp.copy(place = RvPlace.Other)))
        assertEquals(RuleLead.Flag, ruleLead(node, null))
        assertEquals(RuleLead.Direct, ruleLead(null, null))
    }

    @Test
    fun `the cards read the clock, the rule count and the groups' kinds, and honk has no bytes by the day`() {
        val cards = map(data()).cards
        // 13.5 falls in hour 13: hours 0 to 13 are drawn, the 10 still to come are not.
        assertEquals(14, cards.today.down.size)
        assertEquals(0L, cards.today.total)
        assertEquals(0L, cards.yesterdayByNow)
        assertEquals(13.5f, cards.nowHour)
        assertEquals(1_000L, cards.nowEpochMs)
        assertEquals(14, cards.ruleCount)
        assertEquals("Tokyo 02", cards.node("n-00019bac00000000")?.name)
        assertEquals(RvGroupKind.Select, cards.groupKind("g-Streaming"))
        assertEquals(RvGroupKind.Auto, cards.groupKind("g-Auto"))
    }

    @Test
    fun `a protocol is named as the pages name it, an unknown one as the core sends it`() {
        assertEquals("VLESS", protocolName("vless"))
        assertEquals("Hysteria2", protocolName("hysteria2"))
        assertEquals("Shadowsocks", protocolName("ss"))
        assertEquals("wireguard", protocolName("wireguard"))
    }

    @Test
    fun `nodes are every group's node once, subscriptions keep only the link's host`() {
        val mapped = map(data())
        assertEquals(48, mapped.nodes.size)
        assertEquals(mapped.nodes.size, mapped.nodes.distinctBy { it.id }.size)
        assertEquals("Harbor Cloud", mapped.nodes.first { it.id == "n-0001938f00000000" }.subscription)
        assertEquals(listOf("sub.harbor-cloud.example", "lantern-net.example"), mapped.subscriptions.map { it.host })
    }

    @Test
    fun `each failure has its notice, and its action follows the cause`() {
        val expected = mapOf(
            Status.Failed(FailureReason.NoAllowedApps) to (RvActivityNotice.StartNoApps to ActivityRemedy.PerApp),
            Status.Failed(FailureReason.IncompatibleCore) to (RvActivityNotice.StartIncompatible to ActivityRemedy.CheckUpdate),
            Status.Failed(FailureReason.StartFailed, ErrorCode.NoNodes) to (RvActivityNotice.StartNoNodes to ActivityRemedy.AddSubscription),
            Status.Failed(FailureReason.StartFailed) to (RvActivityNotice.StartFailed to ActivityRemedy.Reconnect),
            Status.Failed(FailureReason.VpnNotEstablished) to (RvActivityNotice.StartFailed to ActivityRemedy.Reconnect),
            Status.Failed(FailureReason.LockdownEnabled) to (RvActivityNotice.Lockdown to ActivityRemedy.VpnSettings),
            Status.Failed(FailureReason.CoreStopped) to (RvActivityNotice.CoreStopped to ActivityRemedy.Reconnect),
            Status.Failed(FailureReason.Revoked) to (RvActivityNotice.VpnTaken to ActivityRemedy.Reconnect),
        )
        expected.forEach { (status, want) ->
            val notice = activityNotice(hasSubscriptions = true, status = status, crashOffered = false, allFailed = false)
            assertEquals(status.toString(), want.first, notice)
            assertEquals(status.toString(), want.second, notice?.remedy)
        }
    }

    @Test
    fun `only a cause that may pass on its own is retried`() {
        val retried = RvActivityNotice.entries.filter { it.remedy == ActivityRemedy.Reconnect }
        val starts = listOf(RvActivityNotice.StartFailed, RvActivityNotice.StartNoApps, RvActivityNotice.StartIncompatible, RvActivityNotice.StartNoNodes)
        assertEquals(listOf(RvActivityNotice.StartFailed), starts.filter { it in retried })
        assertEquals(ActivityRemedy.Retest, RvActivityNotice.NoNode.remedy)
        assertEquals(ActivityRemedy.ExportReport, RvActivityNotice.Crash.remedy)
    }

    @Test
    fun `one notice at a time, in order`() {
        val failed = Status.Failed(FailureReason.CoreStopped)
        assertEquals(RvActivityNotice.NoSubscription, activityNotice(false, failed, crashOffered = true, allFailed = true))
        assertEquals(RvActivityNotice.CoreStopped, activityNotice(true, failed, crashOffered = true, allFailed = true))
        assertEquals(RvActivityNotice.Crash, activityNotice(true, Status.Stopped, crashOffered = true, allFailed = true))
        assertEquals(RvActivityNotice.NoNode, activityNotice(true, Status.Stopped, crashOffered = false, allFailed = true))
        assertNull(activityNotice(true, Status.Stopped, crashOffered = false, allFailed = false))
        listOf(Status.Running(0), Status.Starting, Status.Stopping).forEach { status ->
            assertNull(status.toString(), activityNotice(true, status, crashOffered = false, allFailed = false))
        }
    }

    @Test
    fun `the notices from outside the engine take their places in the order`() {
        val all = ActivitySignals(online = false, vpnRefused = true, notifyRefused = true)
        val failed = Status.Failed(FailureReason.CoreStopped)
        // A refused consent is the user's latest answer: it comes before a failure, but only while not connected.
        assertEquals(RvActivityNotice.ConsentDenied, activityNotice(true, failed, crashOffered = true, allFailed = true, all))
        assertEquals(RvActivityNotice.ConsentDenied, activityNotice(true, Status.Stopped, crashOffered = true, allFailed = true, all))
        assertEquals(RvActivityNotice.Offline, activityNotice(true, Status.Running(0), crashOffered = true, allFailed = true, all))
        assertEquals(RvActivityNotice.NoSubscription, activityNotice(false, failed, crashOffered = true, allFailed = true, all))
        // No network after a failure, before a crash log; a refused notification permission comes last.
        val offline = ActivitySignals(online = false, notifyRefused = true)
        assertEquals(RvActivityNotice.CoreStopped, activityNotice(true, failed, crashOffered = true, allFailed = true, offline))
        assertEquals(RvActivityNotice.Offline, activityNotice(true, Status.Stopped, crashOffered = true, allFailed = true, offline))
        val notify = ActivitySignals(notifyRefused = true)
        assertEquals(RvActivityNotice.NoNode, activityNotice(true, Status.Stopped, crashOffered = false, allFailed = true, notify))
        assertEquals(RvActivityNotice.NotifyDenied, activityNotice(true, Status.Running(0), crashOffered = false, allFailed = false, notify))
        assertEquals(ActivityRemedy.Reconnect, RvActivityNotice.ConsentDenied.remedy)
        assertEquals(ActivityRemedy.NetworkSettings, RvActivityNotice.Offline.remedy)
        assertEquals(ActivityRemedy.NotifySettings, RvActivityNotice.NotifyDenied.remedy)
    }

    @Test
    fun `every node unavailable is the no-node notice`() {
        val mapped = map(data(profile(ActivityHonkFixtures.sample().allUnavailable())))
        assertTrue(mapped.allFailed)
        assertEquals(RvActivityNotice.NoNode, activityModel(mapped, Status.Stopped, ActivityTraffic(), false, true, null).notice)
        assertFalse(map(data()).allFailed)
    }

    @Test
    fun `the page model carries honk's traffic, a sample it could not rate as a gap, and waits while loading`() {
        val traffic = activityTraffic(RuntimeTraffic(0, upRate = 3, downRate = 4, upTotal = 0, downTotal = 0, connections = 0), TrafficHistory(0, 1_000, listOf(1, null), listOf(5)))
        val ui = activityModel(map(data()), Status.Running(0), traffic, crashOffered = false, ready = true, failure = null)
        assertEquals(4L, ui.state.downRate)
        assertEquals(listOf(1f, Float.NaN), ui.state.upHistory)
        assertFalse(ui.loading)
        val loading = activityModel(map(data()), Status.Failed(FailureReason.CoreStopped), traffic, false, ready = false, failure = null)
        assertTrue(loading.loading)
        assertNull(loading.notice)
        // A failed read shows no traffic.
        assertEquals(0L, activityTraffic(null, null).downRate)
    }

    @Test
    fun `a tick changes nothing the steady state keeps`() {
        val state = RvActivityState(Status.Running(0), RvRouteMode.Rule, null, 1, 2, listOf(1f), listOf(2f))
        assertEquals(state.steady(), state.copy(downRate = 9, upHistory = listOf(7f)).steady())
    }

    @Test
    fun `the mode action cycles through all three modes and returns to 規則`() {
        val visited = generateSequence(Mode.Rule) { it.routeMode().next() }.take(4).toList()
        assertEquals(listOf(Mode.Rule, Mode.Global, Mode.Direct, Mode.Rule), visited)
    }

    @Test
    fun `the hour of the day follows the zone`() {
        assertEquals(13.5f, hourOf(13L * 3_600_000 + 1_800_000, java.time.ZoneOffset.UTC))
    }
}
