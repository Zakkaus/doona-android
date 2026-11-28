package dev.doona.android.feature.policies

import dev.doona.android.feature.model.RvMatchKind
import dev.doona.android.feature.model.RvRuleMatch
import org.junit.Assert.assertEquals
import org.junit.Test

/** Conditions as honk's routing reference (`doc/en/reference/routing.md`) writes them. */
class HonkRuleTextTest {
    @Test
    fun theConditionEndsAtTheFirstArrowOutsideQuotes() {
        assertEquals("domain(full: 'a->b.example')", ruleCondition("domain(full: 'a->b.example') -> proxy"))
        assertEquals("l4proto(udp) && dport(443)", ruleCondition("l4proto(udp) && dport(443) -> block(must)"))
    }

    @Test
    fun domainMatchersReadAsTheRulePageWordsThem() {
        assertValue(RvMatchKind.Suffix, "example.com", rvRuleMatch("domain(example.com)"))
        assertValue(RvMatchKind.Suffix, "example.com", rvRuleMatch("domain(suffix: example.com)"))
        assertValue(RvMatchKind.Full, "example.com", rvRuleMatch("domain(full: 'example.com')"))
        assertValue(RvMatchKind.Keyword, "google", rvRuleMatch("domain(keyword:google)"))
        assertEquals("cn", (rvRuleMatch("domain(geosite:cn)") as RvRuleMatch.GeoSite).category)
        val set = rvRuleMatch("domain(geosite: netflix, suffix: imgur.com)") as RvRuleMatch.Domains
        assertEquals("netflix" to 2, set.first to set.count)
    }

    @Test
    fun addressMatchersReadAsTheirRangeCountryOrTheLan() {
        assertEquals(RvRuleMatch.Lan, rvRuleMatch("dip(geoip:private)"))
        assertEquals("CN", (rvRuleMatch("dip(geoip: cn)") as RvRuleMatch.GeoIp).code)
        assertValue(RvMatchKind.Cidr, "198.51.100.0/24", rvRuleMatch("dip(198.51.100.0/24)"))
        assertEquals(RvMatchKind.Cidr to "2001:db8::/64", formMatch("dip(2001:db8::/64)"))
        assertEquals(null, formMatch("dip(::::/64)"))
    }

    @Test
    fun anythingElseShowsItsText() {
        for (condition in listOf("l4proto(udp) && dport(443)", "!domain(geosite:cn)", "pname(curl)", "domain(regex: '^api[0-9]+')", "dip(224.0.0.0/3, 'ff00::/8')")) {
            assertValue(null, condition, rvRuleMatch(condition))
        }
    }

    private fun assertValue(kind: RvMatchKind?, value: String, match: RvRuleMatch) {
        val read = match as RvRuleMatch.Value
        assertEquals(kind to value, read.kind to read.value)
    }
}
