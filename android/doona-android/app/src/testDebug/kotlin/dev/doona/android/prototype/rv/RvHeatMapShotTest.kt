package dev.doona.android.prototype.rv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import dev.doona.android.designsystem.rv.Rv
import dev.doona.android.designsystem.rv.RvGutter
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.designsystem.rv.rvHeatTone
import dev.doona.android.prototype.rv.gallery.HEAT_COUNTS
import dev.doona.android.prototype.rv.gallery.HEAT_LEVELS
import dev.doona.android.prototype.rv.gallery.RvGalleryHeatMap
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The library's heat map (`proto-rv-heat-map`), as the gallery draws it: four levels over 24 columns in a card; light,
 * dark and fs200. Then what it does: a cell's tone is its share of the row's busiest column in fifths, rounded up, and
 * each row's cells are one node a screen reader hears in words.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvHeatMapShotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    @Config(qualifiers = "+h420dp")
    fun heatMapLight() = compose.rvShot("heat-map", dark = false) { Page { RvGalleryHeatMap() } }

    @Test
    @Config(qualifiers = "+h420dp")
    fun heatMapDark() = compose.rvShot("heat-map", dark = true) { Page { RvGalleryHeatMap() } }

    @Test
    @Config(qualifiers = "+h420dp")
    fun heatMapFs200() = compose.rvShot("heat-map", dark = false, fontScale = RV_FS200) { Page { RvGalleryHeatMap() } }

    @Test
    fun aCellTakesItsShareOfTheRowsPeakInFifths() {
        assertEquals(0, rvHeatTone(0, 40))
        assertEquals(1, rvHeatTone(1, 40))
        assertEquals(1, rvHeatTone(8, 40))
        assertEquals(2, rvHeatTone(9, 40))
        assertEquals(3, rvHeatTone(22, 40))
        assertEquals(5, rvHeatTone(40, 40))
        assertEquals(0, rvHeatTone(3, 0))
    }

    @Test
    fun eachRowIsLabelledAndSpoken() {
        compose.setContent { Themed { Page { RvGalleryHeatMap() } } }
        HEAT_LEVELS.forEachIndexed { i, level ->
            compose.onNodeWithText(level).assertExists()
            compose.onNodeWithContentDescription("$level：${HEAT_COUNTS[i].sum()} 筆").assertExists()
        }
    }

    @Composable
    private fun Themed(content: @Composable () -> Unit) = RvTheme(seedColorScheme(Seed.Cyan.argb, false), false, content)

    /** The page's colour, the card on the gutter under the status bar, as the library's other part goldens. */
    @Composable
    private fun Page(content: @Composable () -> Unit) {
        Column(Modifier.fillMaxSize().background(Rv.colors.page).padding(top = TOP)) { RvGutter { content() } }
    }

    private companion object {
        val TOP = 72.dp
    }
}
