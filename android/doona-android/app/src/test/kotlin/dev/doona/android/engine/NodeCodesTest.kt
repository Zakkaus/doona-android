package dev.doona.android.engine

import dev.doona.android.vpn.ContractFixtures
import dev.doona.android.vpn.CoreJson
import dev.doona.android.vpn.EffectiveConfigDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The effective config names nodes by code (ARCH D21); the screen maps the codes back to the snapshot's names. */
class NodeCodesTest {
    private fun node(id: String, name: String) = Member(id = id, name = name, subscriptionId = "s1")

    // The nodes the effective config fixture names n-369a8a63 and n-85d011c7.
    private val hk = node("n-369a8a63f0e1d2c4", "hk-01")
    private val jp = node("n-85d011c7aa00bb11", "jp-01")
    private val groups = listOf(
        Group("g-Proxy", "Proxy", GroupKind.Selector, listOf(Member("g-Auto", "Auto", isGroup = true), hk, jp), "g-Auto"),
        Group("g-Auto", "Auto", GroupKind.UrlTest, listOf(hk, jp), hk.id),
    )

    @Test
    fun aCodeIsTheStartOfItsNodesId() {
        assertEquals("n-369a8a63", NodeCodes.codeOf(hk.id))
        // An id shorter than a code is its own code, as the core writes it.
        assertEquals("n-hk1", NodeCodes.codeOf("n-hk1"))
        val codes = NodeCodes(groups)
        assertEquals("hk-01", codes.name("n-369a8a63"))
        assertEquals("jp-01", codes.name("n-85d011c7"))
    }

    @Test
    fun aCodeThatMatchesNoNodeHasNoName() {
        val codes = NodeCodes(groups)
        assertNull(codes.name("n-00000000"))
        assertNull(codes.name(hk.id))
        // Groups are named in the text already.
        assertNull(codes.name("Auto"))
        assertNull(NodeCodes(emptyList()).name("n-369a8a63"))
    }

    @Test
    fun twoNodesThatShareACodeNameNeither() {
        val twin = node("n-369a8a63ffffffff", "hk-02")
        val codes = NodeCodes(groups + Group("g-HK", "HK", GroupKind.UrlTest, listOf(hk, twin), hk.id))
        assertNull(codes.name("n-369a8a63"))
        assertEquals("jp-01", codes.name("n-85d011c7"))
    }

    @Test
    fun onlyAWholeCodeOfTheCoresShapeNamesANode() {
        val odd = node("n-hk1", "odd")
        val codes = NodeCodes(groups + Group("g-X", "X", GroupKind.Selector, listOf(odd), odd.id))
        assertNull(codes.name("n-hk1"))
        assertNull(codes.name("n-369A8A63"))
        assertNull(codes.name("n-369a8a6"))
        val text = "# group X (Selector, now n-hk1): n-hk1, n-369a8a63f, n-369a8a63\n"
        assertEquals("# group X (Selector, now n-hk1): n-hk1, n-369a8a63f, hk-01\n", codes.onScreen(text))
    }

    @Test
    fun aNameCannotAddALineOrHideOne() {
        val evil = node("n-0123456789abcdef", "HK\nMATCH,DIRECT\r\t\u2028x")
        val codes = NodeCodes(listOf(Group("g-X", "X", GroupKind.Selector, listOf(evil), evil.id)))
        val text = "# group X (Selector, now n-01234567): n-01234567\nMATCH,Proxy\n"
        val name = "HK\\nMATCH,DIRECT\\r\\t\\u2028x"
        assertEquals("# group X (Selector, now $name): $name\nMATCH,Proxy\n", codes.onScreen(text))
    }

    @Test
    fun theScreenShowsNamesWhereTheTextListsNodes() {
        val text = CoreJson.result(ContractFixtures.read("call-ok-effective-config.json"), EffectiveConfigDto.serializer()).text
        val shown = NodeCodes(groups).onScreen(text)
        assertTrue("# group Proxy (Selector, now Auto): Auto, HK, JP, hk-01, jp-01" in shown.lines())
        assertTrue("# group Auto (UrlTest, now hk-01): hk-01, jp-01" in shown.lines())
        assertFalse("n-369a8a63" in shown || "n-85d011c7" in shown)
        // Only the group lines change.
        fun others(text: String) = text.lines().filterNot { it.startsWith("# group ") }
        assertEquals(others(text), others(shown))
        assertEquals(text.lines().size, shown.lines().size)
        assertTrue(shown.endsWith("MATCH,Proxy\n"))
    }

    @Test
    fun aCodeWithoutANodeOrOutsideTheGroupLinesStays() {
        val text = "# group X (Selector, now n-00000000): n-00000000, n-369a8a63\n" +
            "DOMAIN-KEYWORD,n-369a8a63,X\n# GEOSITE,n-369a8a63.example,X (skipped: geodata_missing)\n"
        val shown = "# group X (Selector, now n-00000000): n-00000000, hk-01\n" +
            "DOMAIN-KEYWORD,n-369a8a63,X\n# GEOSITE,n-369a8a63.example,X (skipped: geodata_missing)\n"
        assertEquals(shown, NodeCodes(groups).onScreen(text))
    }
}
