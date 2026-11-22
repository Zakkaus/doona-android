package dev.doona.android.designsystem

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import dev.doona.android.designsystem.components.DoonaMeter
import dev.doona.android.designsystem.components.LayoutRole
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.testing.FontScale115
import dev.doona.android.testing.FontScale200
import dev.doona.android.testing.Light
import dev.doona.android.testing.Variant
import dev.doona.android.testing.layoutTree
import dev.doona.android.testing.setVariantContent
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val SHORT = "42 / 100 GB"
private const val LONG = "已使用 70.5 GB，共 100 GB；下次重設日期為 2027-01-01，到期日為 2027-03-15，請在到期前續訂"

/** However long the value text and however large the font, its last line ends a `tight` above the bar and never on it. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class MeterGapTest(private val variant: Variant, private val text: String) {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theValueTextNeverTouchesTheBar() {
        compose.setVariantContent(variant) { Column(Modifier.width(300.dp)) { DoonaMeter(0.5f, text) } }
        val bar = compose.layoutTree().descendants().first { it.role == LayoutRole.Bar }
        val textBounds = compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val gap = bar.top - textBounds.bottom / compose.density.density
        val tight = DoonaTheme.spacing.tight.value
        assertTrue("${variant.name}: %.1f dp between the text and the bar, under %.0f".format(gap, tight), gap >= tight - 0.5f)
    }

    companion object {
        @JvmStatic
        @Parameters(name = "{0}-{1}")
        fun parameters(): List<Array<Any>> = listOf(Light, FontScale115, FontScale200).flatMap { v -> listOf(SHORT, LONG).map { arrayOf(v, it) } }
    }
}
