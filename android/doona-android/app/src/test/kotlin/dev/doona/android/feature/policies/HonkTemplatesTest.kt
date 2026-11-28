package dev.doona.android.feature.policies

import dev.doona.android.vpn.honk.HonkConfig
import dev.doona.android.vpn.honk.HonkDnsRule
import dev.doona.android.vpn.honk.HonkDnsRules
import dev.doona.android.vpn.honk.HonkSource
import dev.doona.android.vpn.honk.HonkSourceAt
import dev.doona.android.vpn.honk.daeBlock
import dev.doona.android.vpn.honk.daeChildren
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HonkTemplatesTest {
    @Test
    fun aTemplateWrittenWholeIsThatTemplateWithOrWithoutQuic() {
        for (id in HonkTemplates.ids) {
            val routing = checkNotNull(HonkRouting.of(config(HonkTemplates.text(id))))
            assertEquals(id, routing.template)
            assertFalse(routing.blockQuic)
            val quic = checkNotNull(HonkRouting.of(config(routing.withBlockQuic(true))))
            assertEquals(id, quic.template)
            assertTrue(quic.blockQuic)
            assertEquals(HonkTemplates.text(id), quic.withBlockQuic(false))
        }
    }

    @Test
    fun routingEditedByHandOrSplitOverFilesIsNoTemplate() {
        val edited = HonkTemplates.text("gfw").replace("fallback:", "domain(example.com) -> direct\n    fallback:")
        assertNull(checkNotNull(HonkRouting.of(config(edited))).template)
        val split = HonkConfig(listOf(source("a", HonkTemplates.text("gfw")), source("b", "routing {\n    fallback: direct\n}\n", "include")))
        assertNull(HonkRouting.of(split))
        assertNull(HonkRouting.of(config("global {\n    log_level: info\n}\n")))
    }

    @Test
    fun adsGoFirstIntoTheRequestsHonkRunsAndOutOfTheLineHonkReadThemFrom() {
        val text = HonkTemplates.text("cn-direct")
        val lines = text.lines()
        val fallback = lines.indexOfFirst { it.trim() == "fallback: cloudflare" } + 1
        val off = dns(rule("qname(geosite:cn) -> alidns", fallback - 1), rule("fallback: cloudflare", fallback, "fallback"))
        assertFalse(blocksAds(off))
        assertNull(adsEdit(config(text), off, on = false))
        val (source, added) = checkNotNull(adsEdit(config(text), off, on = true))
        assertEquals("main", source.id)
        val at = added.lines().indexOfFirst { it.trim() == "qname(geosite:category-ads-all) -> reject" }
        assertEquals(lines.indexOfFirst { it.trim() == "request {" } + 1, at)
        assertEquals(lines[at].takeWhile { it == ' ' }, added.lines()[at].takeWhile { it == ' ' })
        val on = dns(rule("qname(geosite:category-ads-all) -> reject", at + 1), rule("fallback: cloudflare", fallback + 1, "fallback"))
        assertTrue(blocksAds(on))
        assertNull(adsEdit(config(added), on, on = true))
        assertEquals(text, checkNotNull(adsEdit(config(added), on, on = false)).second)
    }

    @Test
    fun aTemplateTakesTheRoutingKeepsTheFilesGroupsAddsItsOwnAndKeepsQuic() {
        val hand = HonkTemplates.text("cn-direct").replace("    fallback: proxy\n", "    domain(suffix: corp.example) -> direct\n    fallback: proxy\n")
        val routing = checkNotNull(HonkRouting.of(config(checkNotNull(HonkRouting.of(config(hand))).withBlockQuic(true))))
        assertNull(routing.template)
        val written = routing.withTemplate("services")
        val after = checkNotNull(HonkRouting.of(config(written)))
        assertEquals("services", after.template)
        assertTrue(after.blockQuic)
        val groups = daeChildren(written, "group").map { it.first }
        val own = daeChildren(hand, "group").map { it.first }
        val theirs = daeChildren(HonkTemplates.text("services"), "group").map { it.first }
        assertEquals(own + theirs.filter { it !in own }, groups)
        assertEquals(daeBlock(hand, "dns")?.let { hand.lines().slice(it) }, daeBlock(written, "dns")?.let { written.lines().slice(it) })
    }

    @Test
    fun aFileWithNoGroupsGetsTheTemplatesGroupBlock() {
        val bare = "routing {\n    fallback: direct\n}\n"
        val written = checkNotNull(HonkRouting.of(config(bare))).withTemplate("gfw")
        assertEquals("gfw", checkNotNull(HonkRouting.of(config(written))).template)
        assertEquals(daeChildren(HonkTemplates.text("gfw"), "group").map { it.first }, daeChildren(written, "group").map { it.first })
    }

    private fun config(text: String) = HonkConfig(listOf(source("main", text)))

    private fun source(id: String, text: String, kind: String = "main") = HonkSource(id, "$id.dae", kind, "0".repeat(64), text, true, text.lines().size, "2026-08-15T09:30:00Z")

    private fun rule(expression: String, line: Int, kind: String = "rule") =
        HonkDnsRule("d$line", line, expression, "upstream", null, HonkSourceAt("main.dae", "main", line, 13), kind)

    private fun dns(vararg request: HonkDnsRule) = HonkDnsRules("generation-1", request.toList(), emptyList())
}
