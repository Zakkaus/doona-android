package dev.doona.android.honk

import dev.doona.android.engine.EngineException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Group blocks of honk's dae text read and given node name filters, in the forms of honk's `groups.md`. */
class DaeGroupsTest {
    private val text = """
        |group {
        |    hk {
        |        filter: subtag('airport') && name(keyword: 'HK')
        |        filter: name(regex: '^Hong Kong ') # by name
        |        policy: min_moving_avg
        |    }
        |    proxy {
        |        filter: group('hk')
        |        policy: select
        |    }
        |    all {
        |        policy: min_moving_avg
        |    }
        |    empty {
        |    }
        |}
        |routing {
        |    fallback: proxy
        |}
    """.trimMargin()

    @Test
    fun aGroupWithNoFilterTakesEveryNode() {
        assertTrue(daeGroup(text, "all")!!.takesEvery)
        assertTrue(daeGroup(text, "empty")!!.takesEvery)
        // A nested group is a filter line too: the group takes only what its members select.
        assertFalse(daeGroup(text, "proxy")!!.takesEvery)
        assertFalse(daeGroup(text, "hk")!!.takesEvery)
        assertNull(daeGroup(text, "jp"))
    }

    @Test
    fun aNodeJoinsAfterTheLastFilter() {
        val joined = daeGroup(text, "hk")!!.withNodes(listOf("東京 03", "Osaka"))
        val lines = joined.lines()
        assertEquals("        filter: name(regex: '^Hong Kong ') # by name", lines[3])
        assertEquals("        filter: name('東京 03')", lines[4])
        assertEquals("        filter: name('Osaka')", lines[5])
        assertEquals("        policy: min_moving_avg", lines[6])
        assertEquals(text.lines().size + 2, lines.size)
    }

    @Test
    fun aGroupWithNoBodyGetsTheFilterOneLevelIn() {
        val joined = daeGroup(text, "empty")!!.withNodes(listOf("a"))
        assertEquals(listOf("    empty {", "        filter: name('a')", "    }"), joined.lines().subList(13, 16))
    }

    @Test
    fun aNameWithAQuoteIsQuotedTheOtherWayAndOneWithBothIsRefused() {
        assertTrue("        filter: name(\"it's\")" in daeGroup(text, "hk")!!.withNodes(listOf("it's")).lines())
        assertThrows(EngineException::class.java) { daeGroup(text, "hk")!!.withNodes(listOf("it's \"x\"")) }
    }

    @Test
    fun joinsEditEveryGroupTheTextHoldsAndLeaveTheRest() {
        val joined = daeJoined(text, mapOf("hk" to listOf("a"), "proxy" to listOf("b"), "jp" to listOf("c")))
        val lines = joined.lines()
        assertEquals("        filter: name('a')", lines[4])
        assertEquals(listOf("        filter: group('hk')", "        filter: name('b')"), lines.subList(8, 10))
        assertEquals(text.lines().size + 2, lines.size)
        assertEquals(text, daeJoined(text, mapOf("jp" to listOf("c"))))
    }
}
