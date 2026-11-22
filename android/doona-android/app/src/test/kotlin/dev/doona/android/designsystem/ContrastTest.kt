package dev.doona.android.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import dev.doona.android.designsystem.theme.DoonaColors
import dev.doona.android.designsystem.theme.contrastRatio
import dev.doona.android.testing.Seed
import dev.doona.android.testing.seedColors
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WCAG contrast of every colour role against the fills it is drawn on. The roles derive from the wallpaper's scheme, so
 * the rules hold for the owner's Cyan, a warm Orange and a dark Navy accent, each in light and dark.
 */
class ContrastTest {
    private class Theme(val name: String, val c: DoonaColors) {
        val fills = mapOf("page" to c.page, "card" to c.card, "disc" to c.discOnCard)
    }

    private val themes = Seed.entries.flatMap { seed ->
        listOf(false, true).map { dark -> Theme("${seed.name} ${if (dark) "dark" else "light"}", seedColors(dark, seed)) }
    }

    private class Check(val what: String, val ratio: Float, val minimum: Float)

    private fun assertAll(checks: (Theme) -> List<Check>) {
        val bad = themes.flatMap { t -> checks(t).map { Check("${t.name}: ${it.what}", it.ratio, it.minimum) } }.filter { it.ratio < it.minimum }
        assertTrue(bad.joinToString("\n") { "%s is %.2f:1, under %.1f".format(it.what, it.ratio, it.minimum) }, bad.isEmpty())
    }

    private fun Theme.on(role: String, color: Color, minimum: Float, fills: List<String> = this.fills.keys.toList()) =
        fills.map { Check("$role on $it", contrastRatio(color, this.fills.getValue(it)), minimum) }

    @Test
    fun textHasFourPointFiveToOne() = assertAll { t ->
        t.on("text", t.c.text, TEXT) + t.on("textSecondary", t.c.textSecondary, TEXT) +
            Check("textSecondary on the selected pill", contrastRatio(t.c.textSecondary, t.c.navPill), TEXT)
    }

    /** The latency qualities are text: a latency's value, a status line, a field's error. */
    @Test
    fun latencyHuesHaveFourPointFiveToOne() = assertAll { t ->
        listOf("good" to t.c.good, "fair" to t.c.fair, "slow" to t.c.slow).flatMap { (name, hue) ->
            t.on(name, hue, TEXT) + Check("$name on the selected pill", contrastRatio(hue, t.c.navPill), TEXT)
        }
    }

    /** A text button sits on the page, a card or a disc fill. */
    @Test
    fun accentTextHasFourPointFiveToOne() = assertAll { t -> t.on("accentText", t.c.accentText, TEXT) }

    @Test
    fun labelsOnFilledButtonsHaveFourPointFiveToOne() = assertAll { t ->
        listOf(
            Check("onPositive on positive", contrastRatio(t.c.onPositive, t.c.positive), TEXT),
            Check("onDestructive on destructive", contrastRatio(t.c.onDestructive, t.c.destructive), TEXT),
        )
    }

    /** The accent fills buttons, checked boxes and the meter, and colours glyphs: 3:1 on what they sit on. */
    @Test
    fun accentFillsAndGlyphsHaveThreeToOne() = assertAll { t -> t.on("positive", t.c.positive, MARK, listOf("page", "card")) }

    /** A control's marks (a checkbox's outline, a chevron, an outlined button's edge) show its state at 3:1. */
    @Test
    fun controlMarksHaveThreeToOne() = assertAll { t -> t.on("control", t.c.control, MARK, listOf("page", "card")) }

    /** The meter's track shows on the card and the page, and its fill is 3:1 on the track. FeedbackTest holds that the meter draws them. */
    @Test
    fun theMeterTrackShowsAndTheFillHasThreeToOne() = assertAll { t ->
        t.on("track", t.c.track, STEP, listOf("page", "card")) + Check("the meter fill on its track", contrastRatio(t.c.positive, t.c.track), MARK)
    }

    /** WCAG 1.4.11 on the switch, on and off: each thumb 3:1 on its track, each track or its rim 3:1 on the card. */
    @Test
    fun theSwitchShowsItsStateAtThreeToOne() = assertAll { t ->
        val onEdge = t.c.switchOnRim ?: t.c.switchOnTrack
        listOf(
            Check("off thumb on the off track", contrastRatio(t.c.switchOffThumb, t.c.control), MARK),
            Check("off track on the card", contrastRatio(t.c.control, t.c.card), MARK),
            Check("on thumb on the on track", contrastRatio(t.c.switchOnThumb, t.c.switchOnTrack), MARK),
            Check("on track or its rim on the card", contrastRatio(onEdge, t.c.card), MARK),
        )
    }

    /** One frame per page: the card is a step lighter than the page in both themes, and a disc on it is not the card's colour. */
    @Test
    fun aCardIsOneStepOffThePage() {
        themes.forEach { t ->
            assertTrue("${t.name}: the card is not lighter than the page", t.c.card.luminance() > t.c.page.luminance())
            assertTrue("${t.name}: the card is %.3f:1 against the page".format(contrastRatio(t.c.card, t.c.page)), contrastRatio(t.c.card, t.c.page) >= STEP)
            assertTrue("${t.name}: a disc on the card is the card's own colour", contrastRatio(t.c.discOnCard, t.c.card) >= STEP)
        }
    }

    /** The one selected fill (the hub bar's current tab, the current node tile) shows on the page it sits on. */
    @Test
    fun theSelectedFillShowsOnThePage() = assertAll { t -> listOf(Check("the selected pill on the page", contrastRatio(t.c.navPill, t.c.page), STEP)) }

    private companion object {
        const val TEXT = 4.5f
        const val MARK = 3f

        /** A step between two fills shows at 1.03:1, the least the page and card of a scheme are apart. */
        const val STEP = 1.03f
    }
}
