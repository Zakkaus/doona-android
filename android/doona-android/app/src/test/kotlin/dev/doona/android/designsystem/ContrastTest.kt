package dev.doona.android.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import dev.doona.android.designsystem.theme.DoonaColors
import dev.doona.android.designsystem.tokens.Dawn
import dev.doona.android.designsystem.tokens.DoonaPalette
import dev.doona.android.designsystem.tokens.Moon
import org.junit.Assert.assertTrue
import org.junit.Test

/** WCAG contrast of every colour role against the fills it is drawn on, in both themes. */
class ContrastTest {
    private fun ratio(a: Color, b: Color): Float {
        val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (hi + 0.05f) / (lo + 0.05f)
    }

    private class Theme(val name: String, val palette: DoonaPalette) {
        val c = DoonaColors(palette)
        val fills = mapOf("window" to c.window, "surface" to c.surface, "card" to c.card, "control" to c.control, "selected" to c.selected)
    }

    private val themes = listOf(Theme("Dawn", Dawn), Theme("Moon", Moon))

    private fun failures(roles: (Theme) -> Map<String, Color>, minimum: Float): List<String> = themes.flatMap { t ->
        roles(t).flatMap { (role, color) ->
            t.fills.map { (fill, bg) -> Triple("${t.name}: $role on $fill", ratio(color, bg), minimum) }
        }
    }.filter { it.second < it.third }.map { "%s is %.2f:1, under %.1f".format(it.first, it.second, it.third) }

    @Test
    fun textHasFourPointFiveToOne() {
        val bad = failures({ t -> t.c.text.let { mapOf("text.primary" to it.primary, "text.secondary" to it.secondary, "text.positive" to it.positive, "text.notice" to it.notice, "text.negative" to it.negative) } }, 4.5f)
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }

    @Test
    fun accentTextOnAccentFillHasFourPointFiveToOne() {
        val bad = themes.map { it to ratio(it.c.onAccent, it.c.accent) }.filter { it.second < 4.5f }
        assertTrue(bad.joinToString { "${it.first.name}: onAccent on accent is %.2f:1".format(it.second) }, bad.isEmpty())
    }

    @Test
    fun accentFillsAndGlyphsHaveThreeToOneOnSheetAndCard() {
        val bad = themes.flatMap { t ->
            listOf("surface", "card").map { fill -> Triple("${t.name}: accent on $fill", ratio(t.c.accent, t.fills.getValue(fill)), 3f) }
        }.filter { it.second < it.third }
        assertTrue(bad.joinToString { "%s is %.2f:1".format(it.first, it.second) }, bad.isEmpty())
    }

    /** WCAG 1.4.11: the parts of a control that show its state are 3:1 against what they sit on. */
    @Test
    fun switchOffShowsItsStateAtThreeToOne() {
        val bad = themes.flatMap { t ->
            listOf("card", "surface").map { fill -> Triple("${t.name}: off outline on $fill", ratio(t.c.switchOffLine, t.fills.getValue(fill)), 3f) } +
                Triple("${t.name}: off knob on the off track", ratio(t.c.switchOffLine, t.c.switchOff), 3f)
        }.filter { it.second < it.third }
        assertTrue(bad.joinToString { "%s is %.2f:1".format(it.first, it.second) }, bad.isEmpty())
    }

    @Test
    fun switchOnKnobHasThreeToOneOnTheAccentTrack() {
        val bad = themes.map { it to ratio(it.c.switchKnob, it.c.accent) }.filter { it.second < 3f }
        assertTrue(bad.joinToString { "${it.first.name}: on knob on the accent track is %.2f:1".format(it.second) }, bad.isEmpty())
    }

    /** The fill the off track had (`hlHigh`) is 1.48:1 on the light card and 2.11:1 on the dark one: this check catches it. */
    @Test
    fun theOldOffTrackFailsTheSameCheck() {
        themes.forEach { t -> assertTrue("${t.name}: hlHigh is ${ratio(t.palette.hlHigh, t.c.card)}:1 on the card", ratio(t.palette.hlHigh, t.c.card) < 3f) }
    }
}
