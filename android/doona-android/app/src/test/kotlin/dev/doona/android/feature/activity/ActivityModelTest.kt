package dev.doona.android.feature.activity

import dev.doona.android.designsystem.rv.RvPlace
import dev.doona.android.engine.Catalog
import dev.doona.android.engine.DayUsage
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.FailureReason
import dev.doona.android.engine.Group
import dev.doona.android.engine.GroupKind
import dev.doona.android.engine.Member
import dev.doona.android.engine.Mode
import dev.doona.android.engine.RuleBytes
import dev.doona.android.engine.RuleKind
import dev.doona.android.engine.RuleRow
import dev.doona.android.engine.RuleTarget
import dev.doona.android.engine.Settings
import dev.doona.android.engine.Status
import dev.doona.android.engine.Subscription
import dev.doona.android.engine.Traffic
import dev.doona.android.engine.Usage
import dev.doona.android.engine.fake.FakeRuleMode
import dev.doona.android.feature.model.RvGroupKind
import dev.doona.android.feature.model.RvRouteMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityModelTest {
    private val n1 = Member("n-1", "Taipei 01", latencyMs = 20)
    private val n2 = Member("n-2", "Tokyo 02", latencyMs = 50, subscriptionId = "s1")
    private val n3 = Member("n-3", "Seoul 03", failed = true)
    private val sub = Subscription("s1", "Harbor", "https://sub.harbor.example/path?token=secret", 3, null)

    /** The first group is not the main one: the main node must come from `mainGroupId`. */
    private val first = Group("g-First", "First", GroupKind.Selector, listOf(n1, n2), "n-1")
    private val auto = Group("g-Auto", "Auto", GroupKind.UrlTest, listOf(n2, n3), "n-2")
    private val main = Group("g-Main", "Main", GroupKind.Selector, listOf(Member("g-Auto", "Auto", isGroup = true), n1), "g-Auto")
    private val streaming = Group("g-Stream", "Streaming", GroupKind.Selector, listOf(n1, n2, n3), Group.Selection("n-1", true), Group.Selection("n-3", true))
    private val ai = Group("g-AI", "AI", GroupKind.Score, listOf(n2), "n-2")

    private fun catalog(mode: Mode = Mode.Rule, groups: List<Group> = listOf(first, auto, main, streaming, ai), subscriptions: List<Subscription> = listOf(sub)) =
        Catalog(mode, groups, subscriptions, "g-Main", FakeRuleMode.SOURCE.copy(rules = 7))

    private fun row(id: String, target: RuleTarget) = RuleRow(id, 0, RuleKind.Domain, "Domain", "example.com", 1, target)

    private val rows = listOf(
        row("r-stream", RuleTarget.Group("g-Stream")),
        row("r-stream2", RuleTarget.Group("g-Stream")),
        row("r-cn", RuleTarget.Direct),
        row("r-main", RuleTarget.Group("g-Main")),
        row("r-ads", RuleTarget.Reject),
        row("r-ai", RuleTarget.Group("g-AI")),
    ).associateBy { it.id }

    private val usage = Usage(
        DayUsage(
            "2026-11-25",
            up = List(24) { 1L },
            down = List(24) { 2L },
            flows = List(24) { 3L },
            outbounds = emptyList(),
            byRule = listOf(
                RuleBytes("r-stream", 10, 20, 1),
                RuleBytes("r-cn", 100, 0, 1),
                RuleBytes("r-stream2", 5, 5, 1),
                RuleBytes("r-main", 1_000, 1_000, 1),
                RuleBytes("r-ads", 500, 500, 1),
                RuleBytes("r-ai", 1, 1, 1),
                RuleBytes("unmatched", 9_000, 9_000, 1),
                RuleBytes("other", 8_000, 8_000, 1),
                RuleBytes("r-gone", 7_000, 7_000, 1),
            ),
        ),
        yesterday = null,
        days = emptyList(),
    )

    private fun data(catalog: Catalog? = catalog(), usage: Usage? = this.usage) =
        ActivityData(catalog, Settings(mode = Mode.Global), usage, rows, yesterdayByNow = 42, nowEpochMs = 1_000, nowHour = 13.5f)

    @Test
    fun `the main node comes from the main group, not the first one, through nested groups`() {
        val mapped = mapCatalog(data())
        assertEquals(RvMainNode("n-2", "Tokyo 02", 50, false), mapped.node)
        assertEquals("n-2", mapped.cards.mainNodeId)
        assertEquals(RvRouteMode.Rule, mapped.mode)
    }

    @Test
    fun `any hand-edited order and naming finds the main group by its id, and none leaves no main node`() {
        // A config the user wrote: no group called Proxy, the main group last, the groups in reverse.
        val renamed = main.copy(id = "g-Out", name = "Out")
        val reordered = Catalog(Mode.Rule, listOf(ai, streaming, auto, first, renamed).reversed(), listOf(sub), "g-Out", FakeRuleMode.SOURCE)
        assertEquals("n-2", mapCatalog(data(reordered)).node?.id)
        assertEquals("n-2", mapCatalog(data(reordered.copy(groups = reordered.groups.reversed()))).node?.id)
        // A main group id that names no group: no node is claimed (the page draws its no-node row), and the rest maps.
        val missing = mapCatalog(data(reordered.copy(mainGroupId = "g-Gone")))
        assertNull(missing.node)
        assertEquals("", missing.cards.mainNodeId)
        assertEquals(listOf(null, "Streaming", "AI"), missing.rules.map { it.label })
    }

    @Test
    fun `direct uses no node, while the cards still know the main group's choice`() {
        val mapped = mapCatalog(data(catalog(Mode.Direct)))
        assertNull(mapped.node)
        assertEquals(RvRouteMode.Direct, mapped.mode)
        assertEquals("n-2", mapped.cards.mainNodeId)
    }

    @Test
    fun `before the catalog is read the mode comes from the settings and nothing claims there is no subscription`() {
        val mapped = mapCatalog(data(catalog = null))
        assertEquals(RvRouteMode.Global, mapped.mode)
        assertNull(mapped.node)
        assertTrue(mapped.hasSubscriptions)
        assertTrue(mapped.rules.isEmpty())
    }

    @Test
    fun `rule rows join today's bytes with the rule rows by target, the most first`() {
        val rules = mapCatalog(data()).rules
        assertEquals(
            listOf(
                RvActivityRule(null, null, null, 100, null),
                RvActivityRule("Streaming", "g-Stream", "n-1", 40, "n-3"),
                RvActivityRule("AI", "g-AI", "n-2", 2, null),
            ),
            rules,
        )
    }

    @Test
    fun `a group whose UDP ends on its TCP node names no second node`() {
        val same = streaming.withSharedSelection("n-1", true)
        val rules = mapCatalog(data(catalog(groups = listOf(first, auto, main, same, ai)))).rules
        assertNull(rules.first { it.groupId == "g-Stream" }.udpNodeId)
    }

    @Test
    fun `a rule row never repeats its title as its subtitle`() {
        val direct = mapCatalog(data()).rules.single { it.label == null }
        // A rule that sends to 直連 ends on no node: its line would be 直連 again, so it has none.
        assertEquals(RuleRowText("直連", null), ruleRowText(direct.label, "直連", "直連"))
        assertEquals(RuleRowText("Streaming", "Taipei 01"), ruleRowText("Streaming", "Taipei 01", "直連"))
        // A group named as its node: the name once.
        assertEquals(RuleRowText("Tokyo 02", null), ruleRowText("Tokyo 02", "Tokyo 02", "直連"))
    }

    @Test
    fun `a node's place is the region the core reads from its name, else none`() {
        val tw = n1.copy(region = "TW")
        val kr = n3.copy(region = "kr")
        val unknown = n2.copy(region = "XX")
        val placed = catalog(groups = listOf(Group("g-Stream", "Streaming", GroupKind.Selector, listOf(tw, unknown, kr), Group.Selection("n-1", true), Group.Selection("n-3", true))))
        val nodes = activityNodes(placed, emptyMap()).byId
        assertEquals(RvPlace.TW, nodes.getValue("n-1").place)
        assertEquals(RvPlace.KR, nodes.getValue("n-3").place)
        // A code the place table does not list places nothing.
        assertEquals(RvPlace.Other, nodes.getValue("n-2").place)
    }

    @Test
    fun `a rule row whose TCP and UDP nodes are in different places leads with its group's glyph`() {
        // The placed group first: a node's first listing is the one mapped.
        val placed = catalog(groups = listOf(streaming.copy(members = listOf(n1.copy(region = "TW"), n2, n3.copy(region = "KR"))), main, auto))
        val catalogNodes = activityNodes(placed, emptyMap())
        val ui = activityCatalog(data(placed), catalogNodes)
        val rule = ui.rules.single { it.groupId == "g-Stream" }
        val node = catalogNodes.byId.getValue(checkNotNull(rule.nodeId))
        val udp = catalogNodes.byId.getValue(checkNotNull(rule.udpNodeId))
        assertEquals(RuleLead.GroupGlyph, ruleLead(node, udp))
        // In one place, or with no place for either, the one flag stands for both.
        assertEquals(RuleLead.Flag, ruleLead(node, udp.copy(place = RvPlace.TW)))
        assertEquals(RuleLead.Flag, ruleLead(node.copy(place = RvPlace.Other), udp.copy(place = RvPlace.Other)))
        assertEquals(RuleLead.Flag, ruleLead(node, null))
        assertEquals(RuleLead.Direct, ruleLead(null, null))
    }

    @Test
    fun `the cards read the day, the clock, the rule count and the groups' kinds`() {
        val cards = mapCatalog(data()).cards
        // 13.5 falls in hour 13: hours 0 to 13 are drawn, the 10 still to come are not.
        assertEquals(14, cards.today.down.size)
        assertEquals(14L * 3, cards.today.total)
        assertEquals(13.5f, cards.nowHour)
        assertEquals(42L, cards.yesterdayByNow)
        assertEquals(1_000L, cards.nowEpochMs)
        assertEquals(7, cards.ruleCount)
        assertEquals("Seoul 03", cards.node("n-3")?.name)
        assertEquals(RvGroupKind.Select, cards.groupKind("g-Stream"))
        assertEquals(RvGroupKind.Auto, cards.groupKind("g-Auto"))
        assertEquals(RvGroupKind.Score, cards.groupKind("g-AI"))
    }

    @Test
    fun `a protocol is named as the pages name it, an unknown one as the core sends it`() {
        assertEquals("VLESS", protocolLabel("vless"))
        assertEquals("Hysteria2", protocolLabel("hysteria2"))
        assertEquals("Shadowsocks", protocolLabel("ss"))
        assertEquals("wireguard", protocolLabel("wireguard"))
    }

    @Test
    fun `nodes are every leaf member once, subscriptions keep only the link's host`() {
        val mapped = mapCatalog(data())
        assertEquals(listOf("n-1", "n-2", "n-3"), mapped.nodes.map { it.id })
        assertEquals("Harbor", mapped.nodes[1].subscription)
        assertEquals(listOf(null), mapped.nodes[2].history)
        val subscription = mapped.subscriptions.single()
        assertEquals("sub.harbor.example", subscription.host)
        assertEquals(0L, subscription.totalBytes)
        // Never fetched: no update time and no format.
        assertNull(subscription.updatedAtEpochMs)
        assertNull(subscription.format)
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
    fun `every node failed is the no-node notice`() {
        val failedOnly = Group("g-Main", "Main", GroupKind.Selector, listOf(n3), "n-3")
        val mapped = mapCatalog(data(catalog(groups = listOf(failedOnly))))
        assertTrue(mapped.allFailed)
        assertEquals(RvActivityNotice.NoNode, activityModel(mapped, Status.Stopped, Traffic(), false, true, null).notice)
        assertFalse(mapCatalog(data()).allFailed)
    }

    @Test
    fun `the page model carries the live traffic and waits while loading`() {
        val traffic = Traffic(upRate = 3, downRate = 4, upHistory = listOf(1, 2), downHistory = listOf(5))
        val ui = activityModel(mapCatalog(data()), Status.Running(0), traffic, crashOffered = false, ready = true, failure = null)
        assertEquals(4L, ui.state.downRate)
        assertEquals(listOf(1f, 2f), ui.state.upHistory)
        assertFalse(ui.loading)
        val loading = activityModel(mapCatalog(data()), Status.Failed(FailureReason.CoreStopped), traffic, false, ready = false, failure = null)
        assertTrue(loading.loading)
        assertNull(loading.notice)
    }

    @Test
    fun `a tick changes nothing the steady state keeps, and the mode action toggles as before`() {
        val state = RvActivityState(Status.Running(0), RvRouteMode.Rule, null, 1, 2, listOf(1f), listOf(2f))
        assertEquals(state.steady(), state.copy(downRate = 9, upHistory = listOf(7f)).steady())
        assertEquals(Mode.Direct, RvRouteMode.Global.toggled())
        assertEquals(Mode.Global, RvRouteMode.Rule.toggled())
        assertEquals(Mode.Global, RvRouteMode.Direct.toggled())
    }

    @Test
    fun `the hour of the day follows the zone`() {
        assertEquals(13.5f, hourOf(13L * 3_600_000 + 1_800_000, java.time.ZoneOffset.UTC))
    }
}

/** [data] mapped over its catalog's nodes, before any node info is read. */
private fun mapCatalog(data: ActivityData) = activityCatalog(data, activityNodes(data.catalog, emptyMap()))
