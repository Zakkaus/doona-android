package dev.doona.android.prototype.rv

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The RV sample's contrast rules, computed from its colour roles in the owner's wallpaper scheme, light and dark, so a
 * token change that breaks one fails here rather than in a golden nobody measures.
 */
class RvColorsTest {
    private val themes = listOf(false, true).map { dark -> RvColors(seedColorScheme(Seed.Cyan.argb, dark), dark) }

    /** Item 1: each thumb 3:1 on its track, each track (or its outline) 3:1 on the card, on and off. */
    @Test
    fun theSwitchReadsInBothThemesAndStates() {
        for (c in themes) {
            val theme = c.name
            assertAtLeast("$theme on thumb on its track", RvContrast.MARK, c.switchOnThumb, c.switchOnTrack)
            assertAtLeast("$theme on track or its outline on the card", RvContrast.MARK, c.switchOnRim ?: c.switchOnTrack, c.card)
            assertAtLeast("$theme off thumb on its track", RvContrast.MARK, c.switchOffThumb, c.control)
            assertAtLeast("$theme off track on the card", RvContrast.MARK, c.control, c.card)
        }
    }

    /**
     * Item 8: in dark the capsule and the top bar's discs are a step above the black page, as Revolut's dark controls are
     * (#232325 on #000000, 1.33:1; on its card #161618, 1.14:1), over the page and over a card alike.
     */
    @Test
    fun darkGlassStandsOffThePageAndTheCard() {
        val c = themes.single { it.dark }
        for ((what, tint) in listOf("capsule" to c.navGlass.over, "top bar disc" to c.controlGlass.over)) {
            assertAtLeast("$what over the page", GLASS_ON_PAGE, tint.compositeOver(c.page), c.page)
            assertAtLeast("$what over a card", GLASS_ON_CARD, tint.compositeOver(c.card), c.card)
        }
    }

    /** Item 22: the capsule's labels keep 4.5:1 over the blur even where the page's text colour lies under them. */
    @Test
    fun navLabelsReadOverAnyRow() {
        for (c in themes) {
            for ((what, under) in listOf("text" to c.text, "page" to c.page, "card" to c.card)) {
                assertAtLeast("${c.name} label over the capsule on $what", RvContrast.TEXT_MIN, c.textSecondary, c.navGlass.over.compositeOver(under))
            }
        }
    }

    /** Item 7 and item 6: the pager's dots on the page, and every subscription's used arc on the card, at 3:1. */
    @Test
    fun pagerDotsAndUsedArcsRead() {
        for (c in themes) {
            assertAtLeast("${c.name} pager dot on the page", RvContrast.MARK, c.control, c.page)
            c.categories.forEachIndexed { i, used -> assertAtLeast("${c.name} used arc $i on the card", RvContrast.MARK, used, c.card) }
        }
    }

    /** O5: the duck's ring, success while connected and the outline colour otherwise, at 3:1 on the hero and on the strip. */
    @Test
    fun duckRingReadsOnTheHeroAndTheStrip() {
        for (c in themes) {
            for ((state, ring) in listOf("connected" to c.good, "disconnected" to c.control)) {
                assertAtLeast("${c.name} $state ring on the hero", RvContrast.MARK, ring, c.page)
                assertAtLeast("${c.name} $state ring on the strip over a card", RvContrast.MARK, ring, c.barGlass.over.compositeOver(c.card))
            }
        }
    }

    private val RvColors.name get() = if (dark) "dark" else "light"

    private fun assertAtLeast(what: String, ratio: Float, a: Color, b: Color) {
        val measured = rvContrast(a, b)
        assertTrue("$what: %.2f:1, needs %.2f:1".format(measured, ratio), measured >= ratio)
    }

    private companion object {
        const val GLASS_ON_PAGE = 1.25f
        const val GLASS_ON_CARD = 1.1f
    }
}
