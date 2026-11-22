package dev.doona.android.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import dev.doona.android.designsystem.theme.DoonaColors
import dev.doona.android.testing.Seed
import dev.doona.android.testing.seedColors
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * A hue carries one meaning. The chart's series colours must not sit near a status colour (the dots and badges that mean
 * connected, fair, failed and untested), and not near each other, or a legend reads as a status.
 */
class SeriesColourTest {
    private class Theme(val name: String, val c: DoonaColors) {
        val statuses = mapOf("good" to c.good, "fair" to c.fair, "slow" to c.slow, "neutral" to c.textSecondary)
        val series = mapOf("download" to c.down.line, "upload" to c.up.line)
    }

    /** The series are the scheme's primary and tertiary: the rules hold for the owner's Cyan, a warm and a dark accent. */
    private val themes = Seed.entries.flatMap { seed ->
        listOf(false, true).map { dark -> Theme("${seed.name} ${if (dark) "dark" else "light"}", seedColors(dark, seed)) }
    }

    /** CIE 1976 colour difference: about 2.3 is just noticeable, 10 is clearly another colour. */
    private fun difference(a: Color, b: Color): Double {
        val (l1, a1, b1) = lab(a)
        val (l2, a2, b2) = lab(b)
        return sqrt((l1 - l2).pow(2) + (a1 - a2).pow(2) + (b1 - b2).pow(2))
    }

    private fun lab(color: Color): Triple<Double, Double, Double> {
        fun linear(v: Float) = v.toDouble().let { if (it <= 0.04045) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
        val r = linear(color.red)
        val g = linear(color.green)
        val b = linear(color.blue)
        val x = (0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.95047
        val y = 0.2126 * r + 0.7152 * g + 0.0722 * b
        val z = (0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.08883
        fun f(t: Double) = if (t > 0.008856) t.pow(1.0 / 3) else 7.787 * t + 16.0 / 116
        return Triple(116 * f(y) - 16, 500 * (f(x) - f(y)), 200 * (f(y) - f(z)))
    }

    private fun contrast(a: Color, b: Color): Float {
        val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (hi + 0.05f) / (lo + 0.05f)
    }

    private fun nearStatus(t: Theme, series: Color): List<String> =
        t.statuses.filter { (_, status) -> difference(series, status) < CLEAR }.map { it.key }

    @Test
    fun noSeriesSitsNearAStatusColour() {
        val bad = themes.flatMap { t -> t.series.flatMap { (name, color) -> nearStatus(t, color).map { "${t.name}: $name is within $CLEAR of the $it colour" } } }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }

    @Test
    fun theSeriesAreClearlyApart() {
        val bad = themes.filter { difference(it.c.down.line, it.c.up.line) < CLEAR }.map { "${it.name}: the two series are within $CLEAR of each other" }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }

    /** A series drawn in a status hue is caught by the same check, in every theme. */
    @Test
    fun aStatusHueFailsTheSameCheck() {
        themes.forEach { t -> assertTrue("${t.name}: the fair hue passed as a series", "fair" in nearStatus(t, t.c.fair)) }
    }

    /** WCAG 1.4.11: a line that carries data is 3:1 against the card and the page it is drawn on. */
    @Test
    fun aSeriesLineHasThreeToOneOnTheCardAndTheSheet() {
        val bad = themes.flatMap { t ->
            t.series.flatMap { (name, color) ->
                listOf("card" to t.c.card, "page" to t.c.page).map { (fill, bg) -> Triple("${t.name}: $name on $fill", contrast(color, bg), 3f) }
            }
        }.filter { it.second < it.third }
        assertTrue(bad.joinToString("\n") { "%s is %.2f:1".format(it.first, it.second) }, bad.isEmpty())
    }

    private companion object {
        const val CLEAR = 10.0
    }
}
