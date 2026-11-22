package dev.doona.android.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import dev.doona.android.designsystem.components.DoonaCard
import dev.doona.android.designsystem.components.DoonaHubBar
import dev.doona.android.designsystem.components.DoonaHubTab
import dev.doona.android.designsystem.components.DoonaNodeTile
import dev.doona.android.designsystem.components.DoonaSegmented
import dev.doona.android.designsystem.components.DoonaText
import dev.doona.android.designsystem.components.LayoutRole
import dev.doona.android.designsystem.theme.DoonaColors
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.testing.Dark
import dev.doona.android.testing.LayoutBox
import dev.doona.android.testing.Light
import dev.doona.android.testing.Variant
import dev.doona.android.testing.layoutTree
import dev.doona.android.testing.seedColors
import dev.doona.android.testing.setVariantContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import dev.doona.android.icons.R as IconsR

/**
 * What the fills of one page add up to, read from the pixels: a page has one frame (the sheet; its cards are a step off
 * it and have no outline), one fill for what is selected (the current tab, the current node) and a filled thumb for the
 * segmented choice, which does not read as a focus ring.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class FillsTest(private val variant: Variant, private val colors: DoonaColors) {
    @get:Rule
    val compose = createComposeRule()

    private fun hex(c: Color) = "#%08X".format(c.toArgb())

    private fun pixel(x: Float, y: Float): Color {
        val density = compose.density.density
        return compose.onRoot().captureToImage().toPixelMap()[(x * density).toInt(), (y * density).toInt()]
    }

    private fun assertFill(what: String, want: Color, got: Color) = assertEquals("$what in ${variant.name}", hex(want), hex(got))

    @Test
    fun theCurrentTabAndTheCurrentNodeShareOneSelectedFill() {
        compose.setVariantContent(variant) {
            Column(Modifier.fillMaxSize().background(DoonaTheme.colors.page)) {
                DoonaNodeTile("hk-01", 42, current = true, groupLabel = null, onClick = {})
                Box(Modifier.weight(1f))
                DoonaHubBar(listOf(DoonaHubTab("A", IconsR.drawable.sym_search), DoonaHubTab("B", IconsR.drawable.sym_edit)), selected = 0, onSelect = {})
            }
        }
        val tree = compose.layoutTree()
        val pill = tree.descendants().first { it.role == LayoutRole.HubBar }.content.first().content.first()
        val tile = compose.onNode(isSelected() and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)).fetchSemanticsNode().boundsInRoot
        assertFill("the current tab's pill", colors.navPill, pixel(pill.left + PAD, pill.centerY))
        assertFill("the current node tile", colors.navPill, pixel(tile.left / compose.density.density + 6f, tile.center.y / compose.density.density))
    }

    @Test
    fun theSegmentedThumbIsFilledNotOutlined() {
        compose.setVariantContent(variant) {
            Box(Modifier.fillMaxSize().background(DoonaTheme.colors.card).padding(PAD.dp)) { DoonaSegmented(listOf("One", "Two"), selected = 0, onSelect = {}) }
        }
        val thumb = compose.onNodeWithText("One").fetchSemanticsNode().boundsInRoot
        val density = compose.density.density
        // Just inside the thumb's left edge and mid-height, where an outline would be: the fill reaches the edge.
        assertFill("the thumb's edge", colors.positive, pixel(PAD + 1f, thumb.center.y / density))
    }

    @Test
    fun aCardHasNoOutlineAndSitsOneStepOffTheSheet() {
        compose.setVariantContent(variant) {
            Box(Modifier.fillMaxSize().background(DoonaTheme.colors.page).padding(PAD.dp)) { DoonaCard(Modifier.fillMaxWidth()) { DoonaText("Card", DoonaTheme.type.body) } }
        }
        val card = compose.onNodeWithText("Card").fetchSemanticsNode().boundsInRoot
        val density = compose.density.density
        // The pixel half a dp inside the card's left edge is where a 1 dp border would be.
        assertFill("the card's edge", colors.card, pixel(PAD + 0.5f, card.center.y / density))
        assertFill("the sheet", colors.page, pixel(PAD / 2, card.center.y / density))
    }

    companion object {
        private const val PAD = 16f

        @JvmStatic
        @Parameters(name = "{0}")
        fun parameters(): List<Array<Any>> = listOf(arrayOf(Light, seedColors(false)), arrayOf(Dark, seedColors(true)))
    }
}
