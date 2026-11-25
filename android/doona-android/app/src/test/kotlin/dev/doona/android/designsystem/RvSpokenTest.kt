package dev.doona.android.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.R
import dev.doona.android.designsystem.rv.Rv
import dev.doona.android.designsystem.rv.RvBusy
import dev.doona.android.designsystem.rv.RvDelta
import dev.doona.android.designsystem.rv.RvInline
import dev.doona.android.designsystem.rv.RvLatencyText
import dev.doona.android.designsystem.rv.RvProgress
import dev.doona.android.designsystem.rv.RvStatRow
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.testing.Seed
import dev.doona.android.testing.seedColorScheme
import dev.doona.android.testing.setDoonaContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** What the library's glyph-and-colour parts say in words: a change's direction, a dash, an arrow, a bar. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xxhdpi")
class RvSpokenTest {
    @get:Rule
    val compose = createComposeRule()

    private fun spoken(text: String) = SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(text))

    private fun themed(content: @Composable () -> Unit) = compose.setDoonaContent { RvTheme(seedColorScheme(Seed.Cyan.argb, false), false) { content() } }

    @Test
    fun aLatencyChangeSaysWhetherTheNodeGotFasterOrSlower() {
        themed {
            RvDelta(-12)
            RvDelta(30)
            RvDelta(0)
        }
        compose.onAllNodes(spoken("變快 12 毫秒")).assertCountEquals(1)
        compose.onAllNodes(spoken("變慢 30 毫秒")).assertCountEquals(1)
        compose.onAllNodes(spoken("無變化")).assertCountEquals(1)
    }

    @Test
    fun aMissingLatencyIsNotTestedNotADash() {
        themed { RvLatencyText(null, false) }
        compose.onAllNodes(spoken("未測試")).assertCountEquals(1)
    }

    @Test
    fun anArrowLineNamesItsDirection() {
        themed { RvInline(R.drawable.rv_sym_arrow_upward, "512 KB/s", Rv.colors.textSecondary, Rv.type.sub, description = "上傳 512 KB/s") }
        compose.onAllNodes(spoken("上傳 512 KB/s")).assertCountEquals(1)
    }

    @Test
    fun aStatRowIsOneStop() {
        themed { RvStatRow("版本", "1.2.3") }
        compose.onAllNodes(hasText("版本") and hasText("1.2.3")).assertCountEquals(1)
    }

    @Test
    fun aBarSaysHowFarItIsAndABusyBarSaysItIsWorking() {
        themed {
            RvProgress(0.25f)
            RvBusy()
        }
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(0.25f, 0f..1f))).assertCountEquals(1)
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate)).assertCountEquals(1)
        compose.onAllNodes(spoken("進行中")).assertCountEquals(1)
    }
}
