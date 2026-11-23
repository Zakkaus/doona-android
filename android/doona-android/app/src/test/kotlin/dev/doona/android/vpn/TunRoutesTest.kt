package dev.doona.android.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunRoutesTest {
    private fun routed(address: String): Boolean = TunRoutes.IPV4.any { Ipv4Prefix.parseAddress(address) in it }

    @Test
    fun publicAddressesAreRouted() {
        listOf("1.1.1.1", "8.8.8.8", "100.64.0.1", "223.255.255.255", "172.32.0.1", "192.169.0.1").forEach {
            assertTrue("$it routed", routed(it))
        }
    }

    @Test
    fun lanLinkLocalLoopbackAndMulticastAreNot() {
        listOf(
            "10.0.0.1",
            "172.20.0.1",
            "192.168.8.161",
            "169.254.1.1",
            "224.0.0.251",
            "255.255.255.255",
            "127.0.0.1",
            "0.0.0.0",
        ).forEach { assertFalse("$it not routed", routed(it)) }
    }

    @Test
    fun theTunsOwnSubnetAndDnsAreRouted() {
        assertTrue(routed(StartConfigDto.IPV4))
        assertTrue(routed(StartConfigDto.DNS))
        assertTrue(Ipv4Prefix.parse("172.19.0.0/30") in TunRoutes.IPV4)
        assertFalse(routed("172.19.0.4"))
    }

    @Test
    fun routesCoverExactlyTheComplementAndTheTun() {
        val routes = TunRoutes.IPV4
        // Prefixes are disjoint or nested, so disjoint routes overlapping no excluded prefix, with the right total,
        // cover exactly the complement.
        val outsideTun = routes - TunRoutes.TUN
        outsideTun.forEach { route -> TunRoutes.EXCLUDED.forEach { assertFalse("$route vs $it", route in it || it in route) } }
        val excluded = TunRoutes.EXCLUDED.sumOf { 1L shl (32 - it.length) }
        assertEquals((1L shl 32) - excluded, outsideTun.sumOf { 1L shl (32 - it.length) })
    }

    @Test
    fun routesAreMinimal() {
        val routes = TunRoutes.IPV4
        assertEquals(routes.size, routes.toSet().size)
        for (a in routes) {
            for (b in routes) {
                if (a == b) continue
                assertFalse("$b inside $a", b in a)
                if (a.length == b.length && a.length > 0) {
                    assertFalse("$a and $b merge", b in Ipv4Prefix.of(a.address, a.length - 1))
                }
            }
        }
    }
}
