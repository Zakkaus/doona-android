package dev.doona.android.prototype.rv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.unit.dp
import dev.doona.android.designsystem.rv.Rv
import dev.doona.android.designsystem.rv.RvGutter
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.designsystem.rv.rvWaffleCells
import dev.doona.android.prototype.rv.gallery.RvGalleryWaffle
import dev.doona.android.prototype.rv.gallery.SPOKEN
import dev.doona.android.prototype.rv.gallery.WAFFLE_COUNTS
import dev.doona.android.prototype.rv.gallery.WAFFLE_LABELS
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The library's waffle (`proto-rv-waffle`), as the gallery draws it: four outcomes over a hundred cells, with the counts at
 * the key's end and with the key alone; light, dark and fs200. Then what it does: the cells always add up to a hundred by
 * largest remainder, a count above zero always gets a cell, and the grid is one node a screen reader hears in words.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvWaffleShotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun waffleLight() = compose.rvShot("waffle", dark = false) { Page { RvGalleryWaffle() } }

    @Test
    fun waffleDark() = compose.rvShot("waffle", dark = true) { Page { RvGalleryWaffle() } }

    @Test
    @Config(qualifiers = "+h1400dp")
    fun waffleFs200() = compose.rvShot("waffle", dark = false, fontScale = RV_FS200) { Page { RvGalleryWaffle() } }

    @Test
    fun cellsAddUpByLargestRemainder() {
        assertArrayEquals(intArrayOf(35, 59, 1, 5), rvWaffleCells(WAFFLE_COUNTS))
        assertArrayEquals(intArrayOf(34, 33, 33), rvWaffleCells(listOf(1, 1, 1)))
        assertArrayEquals(intArrayOf(100, 0), rvWaffleCells(listOf(7, 0)))
        assertArrayEquals(intArrayOf(0, 0), rvWaffleCells(listOf(0, 0)))
    }

    @Test
    fun theGridIsSpokenAndEveryShareKeyed() {
        compose.setContent { RvTheme(seedColorScheme(Seed.Cyan.argb, false), false) { Page { RvGalleryWaffle() } } }
        assertEquals(2, compose.onAllNodesWithContentDescription(SPOKEN).fetchSemanticsNodes().size)
        WAFFLE_LABELS.forEach { assertEquals(2, compose.onAllNodesWithText(it).fetchSemanticsNodes().size) }
    }

    /** The page's colour, the cards on the gutter under the status bar, as the library's other part goldens. */
    @Composable
    private fun Page(content: @Composable () -> Unit) {
        Column(Modifier.fillMaxSize().background(Rv.colors.page).padding(top = TOP)) { RvGutter { content() } }
    }

    private companion object {
        val TOP = 72.dp
    }
}
