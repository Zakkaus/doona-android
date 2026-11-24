package dev.doona.android.prototype.rv

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.doona.android.designsystem.rv.RvAlpha
import dev.doona.android.designsystem.rv.RvCodeKind
import dev.doona.android.designsystem.rv.RvCodeShown
import dev.doona.android.designsystem.rv.RvCodeSpan
import dev.doona.android.designsystem.rv.RvColors
import dev.doona.android.designsystem.rv.RvContrast
import dev.doona.android.designsystem.rv.rvCodeBreaks
import dev.doona.android.designsystem.rv.rvCodeColors
import dev.doona.android.designsystem.rv.rvCodeJoins
import dev.doona.android.designsystem.rv.rvCodeSpans
import dev.doona.android.designsystem.rv.rvCodeStyle
import dev.doona.android.designsystem.rv.rvCodeText
import dev.doona.android.designsystem.rv.rvContrast
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The code text around the tokenizer ([RvCodeLexerTest]): the spans, the break points, the cursor's offsets past them,
 * and every role's contrast in light and dark.
 */
class RvCodeTest {
    @Test
    fun spansSitAtTheirOffsets() {
        val source = "  domain(geosite:cn) -> direct"
        val spans = rvCodeSpans(source)
        assertEquals(RvCodeSpan(RvCodeKind.Call, 2, 8), spans.first())
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
        // A bare address breaks after its path's `/`, not inside `https://`, and not before the hyphen of `dns-query`.
        val url = "remote: https://dns.example.test/dns-query"
        assertArrayEquals(intArrayOf(url.indexOf("/dns-") + 1), rvCodeBreaks(url, rvCodeSpans(url)))
        val host = "  remote: dns.example.test/dns-query"
        assertArrayEquals(intArrayOf(host.indexOf("/dns-") + 1), rvCodeBreaks(host, rvCodeSpans(host)))
        val file = "  data_dir: /var/share/honk"
        val dirs = intArrayOf(file.indexOf("/share") + 1, file.indexOf("/honk") + 1)
        assertArrayEquals(dirs, rvCodeBreaks(file, rvCodeSpans(file)))
        // A product token's `/` is not an address's: no break after it, and a joiner keeps the platform from breaking
        // there, so `Fixture/1.0)` moves whole to the next line (it broke as `(Fixture/` over `1.0)`).
        val product = "  paid: 'https://sub.example.test/list'(Fixture/1.0)"
        assertArrayEquals(intArrayOf(product.indexOf("(F") + 1), rvCodeBreaks(product, rvCodeSpans(product)))
        assertArrayEquals(intArrayOf(product.indexOf("/1.0") + 1), rvCodeJoins(product, rvCodeSpans(product)))
        assertEquals("  paid: 'https://sub.example.test/list'(\u200BFixture/\u20601.0)", RvCodeShown(product).text)
        // An address's `/`, and one in a literal or a comment, takes no joiner.
        assertArrayEquals(intArrayOf(), rvCodeJoins(file, rvCodeSpans(file)))
        assertArrayEquals(intArrayOf(), rvCodeJoins(url, rvCodeSpans(url)))
        assertArrayEquals(intArrayOf(), rvCodeJoins("  a: 'x/y' # p/q", rvCodeSpans("  a: 'x/y' # p/q")))
        val cidr = "  dip(10.8.0.0/16) -> direct"
        assertArrayEquals(intArrayOf(6), rvCodeBreaks(cidr, rvCodeSpans(cidr)))
        val mac = "  mac(00:11:22:33:44:55) -> block"
        assertArrayEquals(intArrayOf(6), rvCodeBreaks(mac, rvCodeSpans(mac)))
    }

    @Test
    fun theCursorMapsPastTheInsertedBreaks() {
        val shown = RvCodeShown("ab(full:ef")
        assertEquals("ab(​full:​ef", shown.text)
        val offsets = shown.offsets
        assertEquals(listOf(0, 1, 2, 4, 5, 6, 7, 8, 10, 11, 12), (0..10).map { offsets.originalToTransformed(it) })
        for (o in 0..10) assertEquals(o, offsets.transformedToOriginal(offsets.originalToTransformed(o)))
        assertEquals(3, offsets.transformedToOriginal(3))
        assertEquals(10, offsets.transformedToOriginal(13))
    }

    /** A long file: the searched mapping and the one-pass colouring agree with counting the breaks one by one. */
    @Test
    fun aLongFileMapsAsCountingEachBreakWould() {
        val source = List(LONG_FILE) { "  domain(geosite:cn) -> direct # 中國" }.joinToString("\n")
        val shown = RvCodeShown(source)
        val breaks = shown.marks
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

    @Test
    fun noRoleChangesTheWeight() {
        // Colour only: every role keeps the weight the code is set in, so a keyword is as wide as plain text (owner, 2026-11-25).
        val code = rvCodeColors(RvColors(seedColorScheme(Seed.Cyan.argb, false), false))
        RvCodeKind.entries.forEach { kind -> assertEquals("$kind", null, code.style(kind).fontWeight) }
        assertEquals("a keyword keeps the accent", code.keyword, code.style(RvCodeKind.Block).color)
    }

    @Test
    fun codeInABoldStyleIsDrawnRegular() {
        // A skipped declaration is a row title (semibold); code is never bolder than in the source field (owner, 2026-11-25).
        val title = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 19.sp)
        assertEquals(FontWeight.Normal, title.rvCodeStyle().fontWeight)
        assertEquals(19.sp, title.rvCodeStyle().fontSize)
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
                    "literal" to code.literal,
                    "call" to code.call,
                    "key" to code.key,
                    "operator" to code.operator,
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

    /** Every role keeps a colour of its own on every seed, light and dark, once each reads on its grounds. */
    @Test
    fun eachRoleHasItsOwnColour() {
        for (seed in SEEDS) {
            for (dark in listOf(false, true)) {
                val code = rvCodeColors(RvColors(seedColorScheme(seed, dark), dark))
                val inks = listOf(code.comment, code.keyword, code.literal, code.call, code.key, code.operator)
                assertEquals("%x %s: two roles share a colour".format(seed, if (dark) "dark" else "light"), inks.size, inks.distinct().size)
            }
        }
    }

    /**
     * On the owner's wallpaper a key, in the theme's tertiary, stays at least [MIN_HUE_DISTANCE] round the colour wheel
     * from the diff's added green and removed red, light and dark. A keyword and a call keep the accent's hue, as the
     * owner asked of keywords, and a literal too, though a cyan accent sits near the added green; the diff marks its lines
     * with + and − and their tint as well.
     */
    @Test
    fun aKeyStaysAwayFromTheDiffsGreenAndRed() {
        for (dark in listOf(false, true)) {
            val c = RvColors(seedColorScheme(Seed.Cyan.argb, dark), dark)
            val key = rvCodeColors(c).key
            for (mark in listOf(c.good, c.error)) {
                assertTrue("${if (dark) "dark" else "light"} ${key.hue()} vs ${mark.hue()}", hueDistance(key.hue(), mark.hue()) >= MIN_HUE_DISTANCE)
            }
        }
    }

    /**
     * Every kind reads apart from plain text and from each kind it meets on a line, at least [MIN_DELTA_E] apart in CIELAB
     * (ΔE*ab, 1976) on every seed, light and dark, and at least [MIN_APART_FROM_TEXT] from plain text. A block, a symbol
     * and a call share the accent's hue (a call drawn far from the text's tone, so on a light theme it is the keyword's
     * ink); a symbol and a literal (a step toward the text) never meet either: an operator or a key stands between
     * (`-> 'remote'`, `direct(mark: 0x10)`).
     */
    @Test
    fun eachKindReadsApartFromPlainTextAndItsNeighbours() {
        val found = ArrayList<String>()
        var least = Float.MAX_VALUE
        for (seed in SEEDS) {
            for (dark in listOf(false, true)) {
                val c = RvColors(seedColorScheme(seed, dark), dark)
                val code = rvCodeColors(c)
                fun ink(kind: RvCodeKind?) = kind?.let { code.style(it).color } ?: c.text
                val pairs = RvCodeKind.entries.map { it to null } + NEIGHBOURS
                for ((a, b) in pairs) {
                    val d = deltaE(ink(a), ink(b))
                    // How far a pair falls short of its floor: plain text's is higher, as a kind must read apart at a glance.
                    least = min(least, d - if (b == null) MIN_APART_FROM_TEXT else MIN_DELTA_E)
                    found += "%x %s %s/%s %.1f".format(seed, if (dark) "dark" else "light", a, b ?: "plain", d)
                }
            }
        }
        assertTrue("short by %.1f:\n%s".format(-least, found.joinToString("\n")), least >= 0f)
    }

    private fun deltaE(a: Color, b: Color): Float {
        val x = a.convert(ColorSpaces.CieLab)
        val y = b.convert(ColorSpaces.CieLab)
        return sqrt((x.red - y.red).pow(2) + (x.green - y.green).pow(2) + (x.blue - y.blue).pow(2))
    }

    private fun Color.hue(): Float {
        val hi = max(red, max(green, blue))
        val lo = min(red, min(green, blue))
        val span = hi - lo
        val sector = when (hi) {
            red -> ((green - blue) / span).mod(SECTORS)
            green -> (blue - red) / span + 2
            else -> (red - green) / span + 4
        }
        return sector * DEGREES_PER_SECTOR
    }

    private fun hueDistance(a: Float, b: Float) = abs(a - b).let { min(it, FULL_TURN - it) }

    private companion object {
        /** Lines in the long file: as many rules as a large routing block holds. */
        const val LONG_FILE = 1_000

        /** The owner's wallpaper, then a purple and a red seed, so the accent of another phone is covered too. */
        val SEEDS = listOf(Seed.Cyan.argb, 0xFF6750A4.toInt(), 0xFFB3261E.toInt())

        const val MIN_HUE_DISTANCE = 30f

        /** The least CIELAB distance between two kinds that meet on a line. */
        const val MIN_DELTA_E = 12f

        /**
         * The least CIELAB distance between a kind and plain text (hl-fix1): the comment's secondary ink, 23.7 on the dark
         * purple seed, is the closest; every other kind is 25 or more.
         */
        const val MIN_APART_FROM_TEXT = 23f

        /** The kinds that meet on a line: `key: 'x'`, `policy: fixed`, `domain(geosite:`, `!geosite`, `{ log_file:`, … */
        val NEIGHBOURS = listOf(
            RvCodeKind.Key to RvCodeKind.Literal, RvCodeKind.Key to RvCodeKind.Symbol, RvCodeKind.Key to RvCodeKind.Call,
            RvCodeKind.Key to RvCodeKind.Operator, RvCodeKind.Key to RvCodeKind.Block, RvCodeKind.Call to RvCodeKind.Literal,
            RvCodeKind.Call to RvCodeKind.Operator, RvCodeKind.Operator to RvCodeKind.Symbol, RvCodeKind.Operator to RvCodeKind.Literal,
            RvCodeKind.Comment to RvCodeKind.Literal, RvCodeKind.Comment to RvCodeKind.Symbol,
            RvCodeKind.Comment to RvCodeKind.Key,
        )
        const val SECTORS = 6f
        const val DEGREES_PER_SECTOR = 60f
        const val FULL_TURN = 360f
    }
}
