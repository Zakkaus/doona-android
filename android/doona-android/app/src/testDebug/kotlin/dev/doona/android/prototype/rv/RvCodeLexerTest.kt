package dev.doona.android.prototype.rv

import dev.doona.android.designsystem.rv.RvCodeKind
import dev.doona.android.designsystem.rv.RvCodeToken
import dev.doona.android.designsystem.rv.rvCodeLines
import dev.doona.android.designsystem.rv.rvCodeSpans
import dev.doona.android.designsystem.rv.rvCodeTokens
import dev.doona.android.prototype.rv.config.rvCodeCorpus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The honk tokenizer against honk-config's grammar: the corpus (`hl-corpus.honk`, every construct) token for token
 * against its golden (`hl-corpus.marked`), the audit's wrong readings as regressions, and the cases that must stay plain.
 */
class RvCodeLexerTest {
    @Test
    fun theCorpusReadsAsItsGolden() {
        val corpus = rvCodeCorpus()
        val tokens = rvCodeTokens(corpus)
        assertEquals("the tokens spell the corpus whole", corpus, tokens.joinToString("") { it.text })
        val marked = marked(tokens)
        val golden = resource("hl-corpus.marked").removeSuffix("\n")
        if (marked != golden) File("build/hl-corpus.marked").writeText(marked + "\n")
        assertEquals(golden, marked)
    }

    /** Negative control: the golden tells the corpus's kinds apart, so a tokenizer that colours nothing fails it. */
    @Test
    fun theGoldenRejectsAPlainReading() {
        val corpus = rvCodeCorpus()
        val golden = resource("hl-corpus.marked").removeSuffix("\n")
        assertFalse(golden == marked(listOf(RvCodeToken(corpus, null))))
        assertEquals("the golden is the corpus with marks", corpus, golden.replace(MARK, "$1"))
        assertEquals(RvCodeKind.entries.map { it.letter }.toSet(), MARK.findAll(golden).map { it.groupValues[0][1] }.toSet())
    }

    @Test
    fun aHashInsideAWordOrAStringStartsNoComment() {
        assertEquals(
            listOf(key("log_file"), lit("\"/tmp/a\\\"#b\""), comment("# c")),
            kinds("log_file: \"/tmp/a\\\"#b\" # c"),
        )
        assertEquals(listOf(key("front")), kinds("front: socks5://192.0.2.10:1080#label"))
        assertEquals(listOf(call("qname"), op("->"), sym("reject")), kinds("qname(a#b) -> reject"))
    }

    @Test
    fun aGroupsEntryIsANameWhateverItsWord() {
        val group = kinds("group {\n  123 { policy: fixed } ui { policy: select }\n}")
        assertEquals(listOf(block("group"), key("123"), key("policy"), sym("fixed"), key("ui"), key("policy"), sym("select")), group)
        assertEquals(listOf(block("assets"), block("geodata")), kinds("assets { geodata {} }"))
    }

    @Test
    fun prefixesAndSymbolsReadTheSameInEveryPlace() {
        assertEquals(listOf(op("!"), key("geosite"), op("->"), sym("block")), kinds("!geosite:cn -> block"))
        assertEquals(listOf(key("default"), sym("block")), kinds("default: block"))
        assertEquals(listOf(call("dport"), lit("443"), op("->"), sym("edge"), sym("must")), kinds("dport(443) -> edge(must)"))
        assertEquals(listOf(key("fallback"), sym("direct"), key("mark"), lit("16")), kinds("fallback: direct(mark: 16)"))
    }

    @Test
    fun aLiteralIsOneToken() {
        assertEquals(listOf(call("dport"), lit("8080-8090")), kinds("dport(8080-8090)"))
        assertEquals(listOf(call("dip"), lit("2001:db8::/32"), lit("198.51.100.0/24")), kinds("dip(2001:db8::/32, 198.51.100.0/24)"))
        assertEquals(listOf(call("mac"), lit("00:11:22:33:44:55"), op("&&"), call("dscp"), lit("0x2e")), kinds("mac(00:11:22:33:44:55) && dscp(0x2e)"))
        assertEquals(listOf(key("check_tolerance"), lit("1.5s")), kinds("check_tolerance: 1.5s"))
        assertEquals(listOf(key("paid"), lit("'https://a.test/l'")), kinds("paid: 'https://a.test/l'(Fixture/1.0)"))
    }

    @Test
    fun aChainHasItsArrowAndACallNeedsItsParenthesis() {
        assertEquals(listOf(key("via"), op("->"), sym("land")), kinds("via: front->land"))
        assertEquals(listOf(lit("443"), op("->"), sym("direct")), kinds("dport (443) -> direct"))
        assertEquals(listOf(call("upstream")), kinds("upstream(cloudflare)"))
        assertEquals(listOf(block("upstream")), kinds("upstream {"))
    }

    /** honk has no `ext:` prefix and no `||`: both stay plain. */
    @Test
    fun extIsNoPrefixAndTwoPipesAreNoOperator() {
        assertEquals(listOf(call("domain")), kinds("domain(ext:foo)"))
        assertEquals(listOf(call("dip"), lit("192.0.2.1")), kinds("dip(192.0.2.1) || pname(curl)").take(2))
        assertTrue(RvCodeToken("||", null) in rvCodeTokens("dip(192.0.2.1) || pname(curl)"))
        assertEquals(listOf(call("group"), op("|")), kinds("group(a|b)"))
    }

    /** A line read alone keeps the kinds it has in its file, but for a group's entries, which need the file. */
    @Test
    fun theLinesOfAFileKeepTheirSpans() {
        val file = "group {\n  ui { policy: fixed }\n}"
        val lines = rvCodeLines(file)
        assertEquals(listOf("group {", "  ui { policy: fixed }", "}"), lines.map { it.text })
        val ui = lines[1]
        assertEquals(RvCodeKind.Key, ui.spans.first().kind)
        assertEquals("ui", ui.text.substring(ui.spans.first().start, ui.spans.first().end))
        assertEquals(rvCodeSpans(file).size, lines.sumOf { it.spans.size })
    }

    private fun kinds(text: String) = rvCodeTokens(text).filter { it.kind != null }

    private fun resource(name: String) = requireNotNull(javaClass.getResource("/dev/doona/android/prototype/rv/$name")).readText()

    private fun marked(tokens: List<RvCodeToken>) = tokens.joinToString("") { t -> t.kind?.let { "«${it.letter}${t.text}»" } ?: t.text }

    private companion object {
        val MARK = Regex("«[BKCSLO#](.*?)»")

        val RvCodeKind.letter get() = if (this == RvCodeKind.Comment) '#' else name[0]

        fun block(t: String) = RvCodeToken(t, RvCodeKind.Block)
        fun key(t: String) = RvCodeToken(t, RvCodeKind.Key)
        fun call(t: String) = RvCodeToken(t, RvCodeKind.Call)
        fun sym(t: String) = RvCodeToken(t, RvCodeKind.Symbol)
        fun lit(t: String) = RvCodeToken(t, RvCodeKind.Literal)
        fun op(t: String) = RvCodeToken(t, RvCodeKind.Operator)
        fun comment(t: String) = RvCodeToken(t, RvCodeKind.Comment)
    }
}
