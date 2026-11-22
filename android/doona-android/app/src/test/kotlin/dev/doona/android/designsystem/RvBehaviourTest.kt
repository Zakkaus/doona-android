package dev.doona.android.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import dev.doona.android.designsystem.components.BarTitleShownKey
import dev.doona.android.designsystem.components.DoonaFloatingNav
import dev.doona.android.designsystem.components.DoonaLargeTitle
import dev.doona.android.designsystem.components.DoonaNavItem
import dev.doona.android.designsystem.components.DoonaRangeChip
import dev.doona.android.designsystem.components.DoonaScrollPage
import dev.doona.android.designsystem.components.DoonaSegmented
import dev.doona.android.designsystem.components.DoonaSwitchRow
import dev.doona.android.designsystem.components.DoonaTrafficChart
import dev.doona.android.designsystem.components.LocalGlassBlur
import dev.doona.android.designsystem.components.glass
import dev.doona.android.designsystem.components.rememberGlass
import dev.doona.android.designsystem.components.rememberNavCollapsed
import dev.doona.android.designsystem.theme.DoonaGlassTint
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.testing.setDoonaContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import dev.doona.android.icons.R as IconsR

/** What the design system's RV components do under a finger, read from their semantics, callbacks and pixels. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w412dp-h915dp-notnight-xhdpi")
class RvBehaviourTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aSwitchRowTogglesFromAnywhereInTheRow() {
        var checked by mutableStateOf(false)
        compose.setDoonaContent { DoonaSwitchRow("Wi-Fi", checked, { checked = it }, Modifier.testTag(ROW)) }
        compose.onNodeWithTag(ROW).performTouchInput { click(Offset(EDGE, centerY)) }
        compose.runOnIdle { assertEquals("a tap on the title", true, checked) }
        compose.onNodeWithTag(ROW).performTouchInput { click(Offset(width - EDGE, centerY)) }
        compose.runOnIdle { assertEquals("a tap on the switch", false, checked) }
        compose.onNodeWithTag(ROW).performTouchInput { click(Offset(centerX, height - EDGE)) }
        compose.runOnIdle { assertEquals("a tap under the title", true, checked) }
    }

    @Test
    fun theSegmentedControlAndTheRangeChipsSelect() {
        compose.setDoonaContent {
            var segment by remember { mutableIntStateOf(0) }
            var range by remember { mutableIntStateOf(0) }
            Column {
                DoonaSegmented(listOf("One", "Two", "Three"), segment, { segment = it })
                Row { listOf("1H", "1D").forEachIndexed { i, label -> DoonaRangeChip(label, i == range, { range = i }) } }
            }
        }
        compose.onNodeWithText("Two").performClick()
        compose.onNodeWithText("Two").assertIsSelected()
        compose.onNodeWithText("One").assertIsNotSelected()
        compose.onNodeWithText("1D").performClick()
        compose.onNodeWithText("1D").assertIsSelected()
        compose.onNodeWithText("1H").assertIsNotSelected()
    }

    @Test
    fun theTrafficChartsScrubReportsTheSampleUnderTheFingerAndClearsOnRelease() {
        val reports = mutableListOf<Int?>()
        compose.setDoonaContent {
            DoonaTrafficChart(listOf(1f, 3f, 2f, 5f, 4f), DoonaTheme.colors.down, { "$it" }, reports.lastOrNull(), { reports += it }, Modifier.testTag(CHART))
        }
        compose.onNodeWithTag(CHART).performTouchInput {
            down(Offset(EDGE, centerY))
            moveTo(Offset(width - EDGE, centerY))
        }
        compose.runOnIdle { assertEquals("first the sample at the left edge, then the last one", listOf(0, 4), reports.toList()) }
        compose.onNodeWithTag(CHART).performTouchInput { up() }
        compose.runOnIdle { assertNull("released", reports.last()) }
    }

    @Test
    fun theNavCollapsesOnScrollDownAndExpandsOnScrollUp() {
        compose.setDoonaContent {
            val scroll = rememberScrollState()
            val glass = rememberGlass()
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().verticalScroll(scroll).testTag(PAGE)) { Spacer(Modifier.height(TALL.dp)) }
                val items = listOf(DoonaNavItem(IconsR.drawable.sym_search, "Home"), DoonaNavItem(IconsR.drawable.sym_link, "Settings"))
                DoonaFloatingNav(items, 0, {}, glass, rememberNavCollapsed(scroll), Modifier.align(Alignment.BottomCenter))
            }
        }
        fun itemHeight() = compose.onNodeWithContentDescription("Home").getUnclippedBoundsInRoot().let { it.bottom - it.top }.value
        val open = itemHeight()
        compose.onNodeWithTag(PAGE).performTouchInput { swipeUp() }
        compose.waitForIdle()
        val shut = itemHeight()
        compose.onNodeWithTag(PAGE).performTouchInput { swipeDown(startY = centerY, endY = centerY + height / 4) }
        compose.waitForIdle()
        assertEquals("the item fills the open capsule", EXPANDED_ITEM, open, TOLERANCE)
        assertEquals("the item fills the collapsed capsule", COLLAPSED_ITEM, shut, TOLERANCE)
        assertEquals("open again on scroll up", EXPANDED_ITEM, itemHeight(), TOLERANCE)
    }

    @Test
    fun theLargeTitleHandsOverToTheBarTitle() {
        compose.setDoonaContent {
            DoonaScrollPage("Settings", Modifier.testTag(PAGE)) { titleBlock ->
                DoonaLargeTitle("Settings", titleBlock)
                Spacer(Modifier.height(TALL.dp))
            }
        }
        val bar = compose.onNode(SemanticsMatcher.keyIsDefined(BarTitleShownKey))
        bar.assert(SemanticsMatcher.expectValue(BarTitleShownKey, false))
        compose.onNodeWithTag(PAGE).performTouchInput { swipeUp() }
        bar.assert(SemanticsMatcher.expectValue(BarTitleShownKey, true))
        compose.onNodeWithTag(PAGE).performTouchInput { swipeDown() }
        bar.assert(SemanticsMatcher.expectValue(BarTitleShownKey, false))
    }

    @Test
    fun withoutTheBlurGlassDrawsItsSolidTint() {
        lateinit var tint: DoonaGlassTint
        var expected = Color.Unspecified
        compose.setDoonaContent {
            val colors = DoonaTheme.colors
            tint = colors.navGlass
            expected = tint.solid.compositeOver(colors.page)
            val glass = rememberGlass()
            CompositionLocalProvider(LocalGlassBlur provides false) {
                Box(Modifier.size(SIDE.dp).background(colors.page).testTag(PAGE)) {
                    Box(Modifier.fillMaxWidth().height(SIDE.dp).glass(glass, RectangleShape, tint))
                }
            }
        }
        val pixels = compose.onNodeWithTag(PAGE).captureToImage().toPixelMap()
        val drawn = pixels[pixels.width / 2, pixels.height / 2]
        for ((name, shift) in listOf("red" to 16, "green" to 8, "blue" to 0)) {
            val got = drawn.toArgb() shr shift and 0xFF
            val want = expected.toArgb() shr shift and 0xFF
            assertTrue("$name of the solid tint over the page: $got, not $want", abs(got - want) <= 1)
        }
    }

    private companion object {
        const val ROW = "row"
        const val CHART = "chart"
        const val PAGE = "page"
        const val EDGE = 4f
        const val TALL = 3000
        const val SIDE = 40

        /** The capsule's pill inset is 4 dp: a 60 dp capsule holds 52 dp items, a 52 dp one 44 dp. */
        const val EXPANDED_ITEM = 52f
        const val COLLAPSED_ITEM = 44f
        const val TOLERANCE = 0.5f
    }
}
