package dev.doona.android.prototype.rv.samples

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The tap and the switch of the 套用到 sample (nbtap, nboff). */
class RvApplyChoiceTest {
    private val apart = RvApplyChoice("a", "b", apart = true)

    @Test
    fun aTapGoesToTheSelectedNetworkAlone() {
        val tcp = apart.tapped("c", RvApplyTo.Tcp)
        assertEquals("c", tcp.tcpId)
        assertEquals("b", tcp.udpId)
        val udp = apart.tapped("c", RvApplyTo.Udp)
        assertEquals("a", udp.tcpId)
        assertEquals("c", udp.udpId)
    }

    @Test
    fun bothMovesTheTwoNetworksTogether() {
        val both = apart.tapped("c", RvApplyTo.Both)
        assertEquals("c", both.tcpId)
        assertEquals("c", both.udpId)
        assertTrue(both.apart)
    }

    @Test
    fun withTheSwitchOffATapAppliesToBothWhateverTheControlSays() {
        val shared = RvApplyChoice("a", "a", apart = false).tapped("c", RvApplyTo.Udp)
        assertEquals("c", shared.tcpId)
        assertEquals("c", shared.udpId)
        assertFalse(shared.apart)
    }

    @Test
    fun switchingOffPutsUdpBackOnTcpsNode() {
        val off = apart.apart(false)
        assertEquals("a", off.tcpId)
        assertEquals("a", off.udpId)
        assertFalse(off.apart)
    }

    @Test
    fun aNodeEitherNetworkChoseIsChosen() {
        assertTrue(apart.chosen("a"))
        assertTrue(apart.chosen("b"))
        assertFalse(apart.chosen("c"))
    }
}
