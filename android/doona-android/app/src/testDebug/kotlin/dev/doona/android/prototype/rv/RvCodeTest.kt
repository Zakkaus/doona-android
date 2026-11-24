package dev.doona.android.prototype.rv

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import dev.doona.android.designsystem.rv.RvAlpha
import dev.doona.android.designsystem.rv.RvCodeHue
import dev.doona.android.designsystem.rv.RvCodeKind
import dev.doona.android.designsystem.rv.RvCodeShown
import dev.doona.android.designsystem.rv.RvCodeSpan
import dev.doona.android.designsystem.rv.RvCodeToken
import dev.doona.android.designsystem.rv.RvColors
import dev.doona.android.designsystem.rv.RvContrast
import dev.doona.android.designsystem.rv.rvCodeBreaks
import dev.doona.android.designsystem.rv.rvCodeColors
import dev.doona.android.designsystem.rv.rvCodeSpans
import dev.doona.android.designsystem.rv.rvCodeText
import dev.doona.android.designsystem.rv.rvCodeTokens
import dev.doona.android.designsystem.rv.rvContrast
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The code highlighter: doona web's `daeTokens.test.ts` cases as they are (the port must read honk text as doona does),
 * then the break points, the cursor's offsets past them, and every role's contrast in light and dark.
 */
class RvCodeTest {
    @Test
    fun classifiesDaeSyntaxAndPreservesEveryCharacter() {
        val source = "routing {\n  filter: name('hk', \"sg\", 10ms) # note\n  l4proto(tcp) -> proxy\n}\n"
        val tokens = rvCodeTokens(source)
        assertEquals(source, tokens.joinToString("") { it.text })
        listOf(
            "routing" to RvCodeKind.Keyword,
            "filter" to RvCodeKind.VariableName,
            "name" to RvCodeKind.PropertyName,
            "'hk'" to RvCodeKind.Quoted,
            "\"sg\"" to RvCodeKind.Quoted,
            "10ms" to RvCodeKind.Number,
            "# note" to RvCodeKind.Comment,
            "->" to RvCodeKind.Operator,
            "proxy" to RvCodeKind.Keyword,
            "{" to RvCodeKind.Punctuation,
        ).forEach { (text, kind) -> assertTrue("$text is $kind in $tokens", RvCodeToken(text, kind) in tokens) }
        assertTrue(RvCodeToken("request", RvCodeKind.Keyword) in rvCodeTokens("  dns {\n  # note\n  request {\n"))
        assertTrue(RvCodeToken("upstream", RvCodeKind.PropertyName) in rvCodeTokens("      upstream(cloudflare) -> accept"))
    }

    @Test
    fun keepsDigitsInsideNamesOutOfTheNumberClass() {
        val tokens = rvCodeTokens("  filter: name(sg-01, hk-02)\n  policy: min_avg10\n  dport(443) && dip(10.0.0.0/24)\n")
        assertEquals(listOf("443", "10.0.0.0/24"), tokens.filter { it.kind == RvCodeKind.Number }.map { it.text })
        assertTrue(RvCodeToken("sg-01", null) in tokens)
        assertTrue(RvCodeToken("min_avg10", null) in tokens)
    }

    @Test
    fun aSkippedRuleReadsAsDoonaReadsIt() {
        val tokens = rvCodeTokens("!pname(helper) && mac(00:11:22:33:44:55) -> block")
        assertEquals(RvCodeToken("!", RvCodeKind.Punctuation), tokens.first())
        assertTrue(RvCodeToken("pname", RvCodeKind.PropertyName) in tokens)
        assertTrue(RvCodeToken("&", RvCodeKind.Punctuation) in tokens)
        assertTrue(RvCodeToken("block", RvCodeKind.Keyword) in tokens)
    }

    @Test
    fun spansSitAtTheirOffsets() {
        val source = "  domain(geosite:cn) -> direct"
        val spans = rvCodeSpans(source)
        assertEquals(RvCodeSpan(RvCodeKind.PropertyName, 2, 8), spans.first())
        spans.forEach { assertTrue(it.end <= source.length && it.start < it.end) }
        assertEquals("direct", spans.last().let { source.substring(it.start, it.end) })
    }

    @Test
    fun aLongTokenMayBreakAfterItsParenthesisAndKeyButNotInsideANumberAStringOrAComment() {
        val rule = "  domain(geosite:category-games@cn) -> direct"
        assertArrayEquals(intArrayOf(9, 17), rvCodeBreaks(rule, rvCodeSpans(rule)))
        val quoted = "  dip(224.0.0.0/3, 'ff00::/8') -> direct # a:b"
        assertArrayEquals(intArrayOf(6), rvCodeBreaks(quoted, rvCodeSpans(quoted)))
        assertArrayEquals(intArrayOf(), rvCodeBreaks("  policy: min()", rvCodeSpans("  policy: min()")))
        val mac = "  mac(00:11:22:33:44:55) -> block"
        assertArrayEquals(intArrayOf(6), rvCodeBreaks(mac, rvCodeSpans(mac)))
    }

    @Test
    fun theCursorMapsPastTheInsertedBreaks() {
        val shown = RvCodeShown("ab(cd:ef")
        assertEquals("ab(​cd:​ef", shown.text)
        val offsets = shown.offsets
        assertEquals(listOf(0, 1, 2, 4, 5, 6, 8, 9, 10), (0..8).map { offsets.originalToTransformed(it) })
        for (o in 0..8) assertEquals(o, offsets.transformedToOriginal(offsets.originalToTransformed(o)))
        assertEquals(3, offsets.transformedToOriginal(3))
        assertEquals(8, offsets.transformedToOriginal(11))
    }

    /** A long file: the searched mapping and the one-pass colouring agree with counting the breaks one by one. */
    @Test
    fun aLongFileMapsAsCountingEachBreakWould() {
        val source = List(LONG_FILE) { "  domain(geosite:cn) -> direct # 中國" }.joinToString("\n")
        val shown = RvCodeShown(source)
        val breaks = shown.breaks
        assertEquals(2 * LONG_FILE, breaks.size)
        for (o in 0..source.length) {
            val t = o + breaks.count { it <= o }
            assertEquals(t, shown.offsets.originalToTransformed(o))
            assertEquals(o, shown.offsets.transformedToOriginal(t))
        }
        val colored = rvCodeText(source, rvCodeColors(RvColors(seedColorScheme(Seed.Cyan.argb, false), false))).spanStyles.filter { it.item.color != Color.Unspecified }
        val expected = shown.spans.map { (it.start + breaks.count { b -> b <= it.start }) to (it.end + breaks.count { b -> b <= it.end }) }
        assertEquals(expected, colored.map { it.start to it.end })
    }

    /** Every role reads at 4.5:1 on the page, the card and a diff's added and removed lines, in both themes. */
    @Test
    fun everyRoleReadsOnThePageTheCardAndTheDiffTints() {
        for (seed in SEEDS) {
            for (dark in listOf(false, true)) {
                val c = RvColors(seedColorScheme(seed, dark), dark)
                val code = rvCodeColors(c)
                val grounds = mapOf("page" to c.page, "card" to c.card) +
                    listOf("added" to c.good, "removed" to c.error).associate { (what, hue) -> what to hue.copy(alpha = RvAlpha.STATUS_CHIP).compositeOver(c.card) }
                val roles = mapOf(
                    "comment" to code.comment,
                    "keyword" to code.keyword,
                    "string" to code.quoted,
                    "number" to code.number,
                    "function" to code.function,
                    "key" to code.key,
                    "punctuation" to code.punctuation,
                )
                for ((role, ink) in roles) {
                    for ((where, ground) in grounds) {
                        val ratio = rvContrast(ink, ground)
                        assertTrue("%x %s %s on %s: %.2f:1".format(seed, if (dark) "dark" else "light", role, where, ratio), ratio >= RvContrast.TEXT_MIN)
                    }
                }
            }
        }
    }

    /**
     * One colour per role, as an editor gives it: seven hues in each theme, none from the scheme, and on every seed seven
     * distinct inks once each reads on its grounds (a dark page is tinted by the seed, so how far an ink lightens may differ).
     */
    @Test
    fun eachRoleHasItsOwnColour() {
        for (hues in listOf(RvCodeHue.light, RvCodeHue.dark)) {
            assertEquals(7, setOf(hues.comment, hues.keyword, hues.string, hues.number, hues.function, hues.key, hues.punctuation).size)
        }
        for (seed in SEEDS) {
            for (dark in listOf(false, true)) {
                val code = rvCodeColors(RvColors(seedColorScheme(seed, dark), dark))
                val inks = listOf(code.comment, code.keyword, code.quoted, code.number, code.function, code.key, code.punctuation)
                assertEquals("%x %s: two roles share a colour".format(seed, if (dark) "dark" else "light"), inks.size, inks.distinct().size)
            }
        }
    }

    private companion object {
        /** Lines in the long file: as many rules as a large routing block holds. */
        const val LONG_FILE = 1_000

        /** The owner's wallpaper, then a purple and a red seed, so the accent of another phone is covered too. */
        val SEEDS = listOf(Seed.Cyan.argb, 0xFF6750A4.toInt(), 0xFFB3261E.toInt())
    }
}
