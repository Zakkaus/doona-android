package dev.doona.android.honk

import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** Subscription entries of honk's dae text read and rewritten, in the forms of honk's `subscription.md`. */
class DaeSubscriptionsTest {
    private val text = """
        |global {
        |    tproxy_port: 12345
        |}
        |subscription {
        |    primary: 'https://example.com/sub' # the main one
        |    'https://example.com/no_tag_link'
        |    detailed: 'https://example.org/sub' {
        |        ua: 'honk/1.0'
        |        interval: 10000s
        |    }
        |    'Harbor Cloud': "https://sub.harbor.example/sub"
        |    only: 'https://only.example/sub' {
        |        ua: "clash.meta"
        |    }
        |    compatible: 'https://example.net/sub'(honk/1.0 like)
        |}
        |
    """.trimMargin()

    private fun entry(name: String) = checkNotNull(daeSubscription(text, name)) { "no entry $name" }

    @Test
    fun eachFormGivesItsUserAgentAndNoneLeavesHonksDefault() {
        assertNull(entry("primary").userAgent)
        assertNull(entry("example.com").userAgent)
        assertEquals("honk/1.0", entry("detailed").userAgent)
        assertNull(entry("Harbor Cloud").userAgent)
        assertEquals("clash.meta", entry("only").userAgent)
        assertEquals("honk/1.0 like", entry("compatible").userAgent)
        assertNull(daeSubscription(text, "missing"))
        assertNull(daeSubscription("routing {\n}\n", "primary"))
    }

    @Test
    fun aRenameRewritesOnlyTheTag() {
        assertEquals(text.replace("    primary: 'https", "    'Harbor 2': 'https"), entry("primary").renamed("Harbor 2"))
        assertEquals(text.replace("    'Harbor Cloud': \"https", "    harbor: \"https"), entry("Harbor Cloud").renamed("harbor"))
        assertEquals(text.replace("    'https://example.com/no_tag_link'", "    office: 'https://example.com/no_tag_link'"), entry("example.com").renamed("office"))
        assertEquals(text.replace("    compatible: 'https", "    \"it's\": 'https"), entry("compatible").renamed("it's"))
    }

    @Test
    fun aNameHonksTextCannotHoldIsRefused() {
        val error = assertThrows(EngineException::class.java) { entry("primary").renamed("a'b\"c") }
        assertEquals(ErrorCode.InvalidArgument, error.code)
        assertEquals(ErrorCode.InvalidArgument, assertThrows(EngineException::class.java) { entry("primary").renamed("a\nb") }.code)
    }

    @Test
    fun aUserAgentOnAOneLineEntryOpensABlockBeforeItsComment() {
        assertEquals(
            text.replace(
                "    primary: 'https://example.com/sub' # the main one\n",
                "    primary: 'https://example.com/sub' { # the main one\n        ua: 'clash.meta'\n    }\n",
            ),
            entry("primary").withUserAgent("clash.meta"),
        )
    }

    @Test
    fun aUserAgentReplacesTheBlocksOrJoinsIt() {
        assertEquals(text.replace("        ua: 'honk/1.0'\n", "        ua: 'mihomo'\n"), entry("detailed").withUserAgent("mihomo"))
        val block = "only: 'https://only.example/sub' {\n        ua: \"clash.meta\"\n    }"
        val empty = text.replace(block, "only: 'https://only.example/sub' {\n    }")
        assertEquals(text.replace("ua: \"clash.meta\"", "ua: 'x'"), daeSubscription(empty, "only")!!.withUserAgent("x"))
        assertEquals(
            text.replace(block, "only: 'https://only.example/sub' {\n        ua: 'a'\n        cache: false\n    }"),
            daeSubscription(text.replace("        ua: \"clash.meta\"\n", "        cache: false\n"), "only")!!.withUserAgent("a"),
        )
    }

    @Test
    fun takingTheUserAgentOutKeepsTheOtherOptionsAndDropsABlockLeftEmpty() {
        assertEquals(text.replace("        ua: 'honk/1.0'\n", ""), entry("detailed").withUserAgent(null))
        assertEquals(
            text.replace("    only: 'https://only.example/sub' {\n        ua: \"clash.meta\"\n    }\n", "    only: 'https://only.example/sub'\n"),
            entry("only").withUserAgent(null),
        )
        assertEquals(text, entry("primary").withUserAgent(null))
    }

    @Test
    fun theLegacySuffixGivesWayToTheBlock() {
        assertEquals(
            text.replace("    compatible: 'https://example.net/sub'(honk/1.0 like)\n", "    compatible: 'https://example.net/sub' {\n        ua: 'clash.meta'\n    }\n"),
            entry("compatible").withUserAgent("clash.meta"),
        )
        assertEquals(text.replace("'https://example.net/sub'(honk/1.0 like)", "'https://example.net/sub'"), entry("compatible").withUserAgent(null))
        assertEquals("agent # build", daeSubscription("subscription {\n    c: 'https://q.example/sub'('agent # build')\n}\n", "c")!!.userAgent)
    }
}
