package dev.doona.android.feature.policies

import dev.doona.android.designsystem.rv.RvPlace
import dev.doona.android.engine.Group
import dev.doona.android.engine.GroupKind
import dev.doona.android.engine.LatencyTest
import dev.doona.android.engine.Member
import dev.doona.android.engine.Mode
import dev.doona.android.engine.NodeInfo
import dev.doona.android.engine.RuleKind
import dev.doona.android.engine.RuleRow
import dev.doona.android.engine.RuleSkip
import dev.doona.android.engine.RuleTarget
import dev.doona.android.engine.Subscription
import dev.doona.android.engine.SubscriptionFormat
import dev.doona.android.engine.fake.RvSampleRules
import dev.doona.android.engine.fake.rvSampleEngine
import dev.doona.android.feature.model.RvGroupKind
import dev.doona.android.feature.model.RvLabel
import dev.doona.android.feature.model.RvMatchKind
import dev.doona.android.feature.model.RvRouteMode
import dev.doona.android.feature.model.RvRuleMatch
import dev.doona.android.feature.model.RvRuleState
import dev.doona.android.feature.model.RvSubFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyMappingTest {
    private fun node(id: String, latencyMs: Int? = null, failed: Boolean = false, subscriptionId: String? = null) =
        Member(id, "Node $id", latencyMs, failed, subscriptionId = subscriptionId)

    private fun nodes(vararg members: Member) = members.associate { it.id to rvNode(it, null, "", RV_UNKNOWN_PLACES) }

    private fun row(kind: RuleKind?, sample: String?, count: Int = 1, kindName: String = kind?.name.orEmpty(), target: RuleTarget = RuleTarget.Direct) =
        RuleRow("r-$kindName-$sample", 0, kind, kindName, sample, count, target)

    @Test
    fun `automatic kinds read as one, Score as its own`() {
        assertEquals(RvGroupKind.Select, rvGroupKind(GroupKind.Selector))
        assertEquals(RvGroupKind.Auto, rvGroupKind(GroupKind.UrlTest))
        assertEquals(RvGroupKind.Auto, rvGroupKind(GroupKind.Fallback))
        assertEquals(RvGroupKind.Score, rvGroupKind(GroupKind.Score))
    }

    @Test
    fun `modes map both ways`() {
        assertEquals(listOf(RvRouteMode.Global, RvRouteMode.Direct, RvRouteMode.Rule), Mode.entries.map(::rvRouteMode))
        Mode.entries.forEach { assertEquals(it, engineMode(rvRouteMode(it))) }
    }

    @Test
    fun `a subscription without a quota has none, and only the host of its link is kept`() {
        val sub = Subscription("s1", "Harbor", "https://user:secret@sub.harbor.example:8443/path?token=abc", 3, updatedAtEpochMs = null)
        val mapped = rvSubscription(sub)
        assertEquals(0L, mapped.usedBytes)
        assertEquals(0L, mapped.totalBytes)
        assertEquals(0L, mapped.updatedAtEpochMs)
        assertFalse(mapped.hasQuota)
        assertEquals("sub.harbor.example", mapped.host)
        assertEquals(RvSubFormat.Links, mapped.format)
        assertEquals("", rvSubscription(sub.copy(url = "not a url")).host)
    }

    @Test
    fun `a subscription keeps its quota, end, format and User-Agent`() {
        val sub = Subscription("s1", "Harbor", "https://h.example/s", 3, 5L, null, 10L, 20L, 30L, "clash", SubscriptionFormat.Clash)
        val mapped = rvSubscription(sub)
        assertEquals(listOf(10L, 20L, 30L, 5L), listOf(mapped.usedBytes, mapped.totalBytes, mapped.expiresAtEpochMs, mapped.updatedAtEpochMs))
        assertEquals("clash", mapped.userAgent)
        val formats = SubscriptionFormat.entries.map { rvSubscription(sub.copy(format = it)).format }
        assertEquals(listOf(RvSubFormat.Links, RvSubFormat.Links, RvSubFormat.Sip008, RvSubFormat.Clash, RvSubFormat.SingBox), formats)
    }

    @Test
    fun `a node takes the core's last tests and endpoint`() {
        val tests = (1..10).map { LatencyTest(it.toLong(), if (it == 9) null else it * 10) }
        val info = NodeInfo("n1", "vless", "hk.example.net", 443, "ws", true, tests)
        val mapped = rvNode(node("n1", subscriptionId = "s1"), info, "Harbor", RV_UNKNOWN_PLACES)
        assertEquals(listOf(30, 40, 50, 60, 70, 80, null, 100), mapped.history)
        assertEquals(listOf("vless", "hk.example.net", "ws"), listOf(mapped.protocol, mapped.host, mapped.transport))
        assertEquals(443, mapped.port)
        assertTrue(mapped.tls)
        assertEquals("Harbor", mapped.subscription)
        assertEquals(RvPlace.Other, mapped.place)
        assertEquals("Node n1", mapped.short)
    }

    @Test
    fun `a node without details shows the catalog's last probe, or nothing before one`() {
        assertEquals(listOf(42), rvNode(node("n1", 42), null, "", RV_UNKNOWN_PLACES).history)
        assertEquals(listOf(null), rvNode(node("n1", failed = true), null, "", RV_UNKNOWN_PLACES).history)
        val untested = rvNode(node("n1"), null, "", RV_UNKNOWN_PLACES)
        assertEquals(emptyList<Int?>(), untested.history)
        assertEquals(listOf("", "", 0, false), listOf(untested.protocol, untested.host, untested.port, untested.tls))
    }

    @Test
    fun `a nested group stands as its leaf node, once, and an unknown selection falls back to the first`() {
        val inner = Group("g-In", "In", GroupKind.UrlTest, listOf(node("n1"), node("n2")), selectedId = "n2")
        val innerMember = Member("g-In", "In", isGroup = true)
        val outer = Group("g-Out", "Out", GroupKind.Selector, listOf(innerMember, node("n1"), node("n2"), node("n3")), selectedId = "n-gone")
        val groups = listOf(inner, outer).associateBy { it.id }
        val all = nodes(node("n1"), node("n2"), node("n3"))
        val mapped = rvPolicyGroup(outer, groups, all)!!
        assertEquals(listOf("n2", "n1", "n3"), mapped.members.map { it.id })
        assertEquals("n2", mapped.selectedId)
        assertEquals(RvLabel.Text("Out"), mapped.name)
        assertEquals("n2", rvPolicyGroup(outer.copy(tcp = Group.Selection("g-In", true)), groups, all)!!.selectedId)
        assertEquals("n3", rvPolicyGroup(outer.copy(tcp = Group.Selection("n3", true)), groups, all)!!.selectedId)
    }

    @Test
    fun `a tap on a node reachable only through a nested group selects that group`() {
        val inner = Group("g-In", "In", GroupKind.UrlTest, listOf(node("n9")), selectedId = "n9")
        val outer = Group("g-Out", "Out", GroupKind.Selector, listOf(Member("g-In", "In", isGroup = true), node("n1")), selectedId = "n1")
        val groups = listOf(inner, outer).associateBy { it.id }
        assertEquals("g-In", memberFor(outer, "n9", groups))
        assertEquals("n1", memberFor(outer, "n1", groups))
    }

    @Test
    fun `a group that leads to no node cannot be drawn`() {
        assertNull(rvPolicyGroup(Group("g-E", "E", GroupKind.Selector, emptyList(), selectedId = null), emptyMap(), emptyMap()))
        val dangling = Group("g-D", "D", GroupKind.Selector, listOf(Member("g-Gone", "Gone", isGroup = true)), selectedId = "g-Gone")
        assertNull(rvPolicyGroup(dangling, mapOf(dangling.id to dangling), emptyMap()))
    }

    @Test
    fun `each rule kind matches as the pages say it`() {
        assertEquals(RvRuleMatch.Rest, rvRuleMatch(row(RuleKind.Match, null, 0)))
        assertEquals("CN", (rvRuleMatch(row(RuleKind.GeoIp, "cn")) as RvRuleMatch.GeoIp).code)
        assertEquals(RvRuleMatch.Lan, rvRuleMatch(row(RuleKind.GeoIp, "private")))
        val domains = rvRuleMatch(row(RuleKind.DomainSuffix, "netflix.com", 12)) as RvRuleMatch.Domains
        assertEquals("netflix.com" to 12, domains.first to domains.count)
        assertTrue(rvRuleMatch(row(RuleKind.Domain, "a.example", 2)) is RvRuleMatch.Domains)
        fun value(kind: RuleKind?, sample: String?, kindName: String = kind?.name.orEmpty()) =
            (rvRuleMatch(row(kind, sample, kindName = kindName)) as RvRuleMatch.Value).let { it.kind to it.value }
        assertEquals(RvMatchKind.Full to "gemini.google.com", value(RuleKind.Domain, "gemini.google.com"))
        assertEquals(RvMatchKind.Suffix to "github.com", value(RuleKind.DomainSuffix, "github.com"))
        assertEquals(RvMatchKind.Keyword to "gamer", value(RuleKind.DomainKeyword, "gamer"))
        assertEquals(RvMatchKind.Cidr to "10.0.0.0/8", value(RuleKind.IpCidr, "10.0.0.0/8"))
        assertEquals(RvMatchKind.Full to "443", value(RuleKind.DstPort, "443"))
        assertEquals(RvMatchKind.Full to "udp", value(RuleKind.Network, "udp"))
        assertEquals(RvMatchKind.Full to "PROCESS-NAME", value(null, null, "PROCESS-NAME"))
    }

    @Test
    fun `a GEOSITE row is a GeoSite match in any case`() {
        assertEquals("google", (rvRuleMatch(row(null, "google", kindName = "GEOSITE")) as RvRuleMatch.GeoSite).category)
        assertTrue(rvRuleMatch(row(null, "apple-cn", kindName = "geosite")) is RvRuleMatch.GeoSite)
    }

    @Test
    fun `a rule goes direct, through its group, or is left out when it refuses`() {
        val all = nodes(node("n1"))
        val group = rvPolicyGroup(Group("g-A", "A", GroupKind.Selector, listOf(node("n1")), selectedId = "n1"), emptyMap(), all)!!
        val groups = mapOf(group.id to group)
        assertNull(rvPolicyRule(row(RuleKind.Domain, "a.example"), groups, 0)!!.group)
        assertEquals("g-A", rvPolicyRule(row(RuleKind.Domain, "a.example", target = RuleTarget.Group("g-A")), groups, 0)!!.group?.id)
        assertNull(rvPolicyRule(row(RuleKind.Domain, "a.example", target = RuleTarget.Reject), groups, 0))
    }

    @Test
    fun `a row is skipped only when all its lines are`() {
        val skipped = row(null, "google", kindName = "GEOSITE").copy(skipped = RuleSkip.GeodataMissing, skippedLines = 1)
        assertEquals(RvRuleState.Skipped, rvPolicyRule(skipped, emptyMap(), 0)!!.state)
        val partly = row(RuleKind.DomainSuffix, "a.example", 3).copy(skipped = RuleSkip.UnsupportedKind, skippedLines = 1)
        assertEquals(RvRuleState.On, rvPolicyRule(partly, emptyMap(), 0)!!.state)
    }

    @Test
    fun `the profile joins bytes by rule id and leaves refusing rows out`() {
        val catalog = rvSampleEngine().catalog.value!!
        val reject = RuleRow("r-ads", 0, RuleKind.DomainSuffix, "DomainSuffix", "ads.example", 1, RuleTarget.Reject)
        val rows = listOf(reject) + RvSampleRules.rows
        val profile = rvPolicyProfile(catalog, emptyMap(), rows, 15, mapOf("r-streaming" to 7L, "r-cn" to 3L), 99L, RV_UNKNOWN_PLACES)!!
        assertEquals("g-Proxy", profile.main.id)
        assertEquals(catalog.groups.map { it.id }.drop(1), profile.groups.map { it.id })
        assertEquals(RV_POLICY_CARD_RULES, profile.rules.size)
        assertEquals(listOf(7L, 0L, 3L, 0L, 0L), profile.rules.map { it.bytes })
        assertEquals(15, profile.ruleCount)
        assertEquals("g-Streaming", profile.rules.first().group?.id)
        assertEquals(RvRuleMatch.Lan, profile.rules[3].match)
        assertEquals(catalog.groups.flatMap { it.members }.filterNot { it.isGroup }.map { it.id }.distinct(), profile.nodes.map { it.id })
        assertEquals(listOf("Harbor Cloud", "Lantern Net"), profile.nodes.map { it.subscription }.distinct())
        assertEquals(listOf("sub.harbor-cloud.example", "lantern-net.example"), profile.subscriptions.map { it.host })
        assertEquals(99L, profile.nowEpochMs)
    }

    @Test
    fun `no profile when the main group cannot be drawn`() {
        val catalog = rvSampleEngine().catalog.value!!
        val empty = catalog.copy(groups = catalog.groups.map { if (it.id == catalog.mainGroupId) it.copy(members = emptyList()) else it })
        assertNull(rvPolicyProfile(empty, emptyMap(), emptyList(), 0, emptyMap(), 0L, RV_UNKNOWN_PLACES))
    }
}
