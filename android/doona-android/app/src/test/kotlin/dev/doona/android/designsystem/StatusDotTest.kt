package dev.doona.android.designsystem

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.designsystem.components.DoonaErrorBanner
import dev.doona.android.designsystem.components.DoonaLatency
import dev.doona.android.designsystem.components.DoonaMetricLatency
import dev.doona.android.designsystem.components.DoonaSmallStatusLight
import dev.doona.android.designsystem.components.DoonaStatusLight
import dev.doona.android.designsystem.components.LayoutRole
import dev.doona.android.designsystem.rv.Tone
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.testing.FontScale200
import dev.doona.android.testing.Light
import dev.doona.android.testing.Variant
import dev.doona.android.testing.layoutTree
import dev.doona.android.testing.setVariantContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A status dot is sized with the text line it sits on: at twice the font size it is twice as big, not a speck beside large text. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class StatusDotTest {
    @get:Rule
    val compose = createComposeRule()

    /** Each dot's width and the height of the text line it sits on, both in dp, smallest dot first. */
    private fun dots(variant: Variant): List<Pair<Float, Float>> {
        compose.setVariantContent(variant) {
            Column {
                DoonaStatusLight(Tone.Ok, "A")
                DoonaSmallStatusLight(Tone.Warn, "B")
                DoonaLatency(42)
                DoonaMetricLatency(42)
                DoonaErrorBanner("Error", "Dismiss", onDismiss = {})
            }
        }
        val tree = compose.layoutTree()
        return tree.descendants().filter { it.role == LayoutRole.Dot }.map { it.width to tree.parentOf(it).height }.toList().sortedBy { it.first / it.second }
    }

    /** A dot's share of its line at the normal font size: 10 of the body's 20, 8 of its 20 and of the figure's 28. */
    private val shares = with(DoonaTheme.size) { listOf(dot.value / FIGURE_LINE, dot.value / BODY_LINE, dot.value / BODY_LINE, statusDot.value / BODY_LINE, statusDot.value / BODY_LINE) }

    @Test
    fun dotsKeepTheirSizeAtTheNormalFontSize() {
        val dots = dots(Light)
        assertEquals(listOf(8f, 8f, 8f, 10f, 10f), dots.map { Math.round(it.first * 10) / 10f }.sorted())
        assertClose(shares.sorted(), dots.map { it.first / it.second }, 0.01f)
    }

    /** The font grows less than linearly from a scale of 1.3 on (Android 14), so the dot follows the line it sits on, which is the measure. */
    @Test
    fun dotsGrowWithTheLineAtTwiceTheFontSize() {
        val dots = dots(FontScale200)
        assertClose(shares.sorted(), dots.map { it.first / it.second }, 0.01f)
        val nominal = with(DoonaTheme.size) { listOf(dot.value, dot.value, dot.value, statusDot.value, statusDot.value) }
        assertTrue("every dot is at least 1.25 times its normal size: $dots", dots.map { it.first }.sorted().zip(nominal).all { (got, normal) -> got >= normal * GROWN })
    }

    private fun assertClose(want: List<Float>, got: List<Float>, tolerance: Float) {
        assertEquals("$want vs $got", want.size, got.size)
        want.zip(got).forEach { (w, g) -> assertEquals("$want vs $got", w, g, tolerance) }
    }

    private companion object {
        const val GROWN = 1.25f
        const val BODY_LINE = 20f
        const val FIGURE_LINE = 28f
    }
}
