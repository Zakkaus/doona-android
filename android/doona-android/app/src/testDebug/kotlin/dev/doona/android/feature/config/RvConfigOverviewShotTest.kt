package dev.doona.android.feature.config

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.prototype.rv.RV_FS115
import dev.doona.android.prototype.rv.RV_FS200
import dev.doona.android.prototype.rv.Seed
import dev.doona.android.prototype.rv.Utc
import dev.doona.android.prototype.rv.assertWhole
import dev.doona.android.prototype.rv.rvShot
import dev.doona.android.prototype.rv.seedColorScheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 設定檔 and its skipped list (PV-f item 2), samples only: the overview with the entry's Linux-only settings skipped
 * (`proto-rv-config-overview-*`), the same with the plan's negative rule skipped in an include
 * (`proto-rv-config-overview-skipped-include-*`), and that list (`proto-rv-config-unsupported-*`). The list holds the
 * whole page at every size, so no rule's text or reason is cut by the screen; the overview's light and dark hold the
 * whole page and its fs115 and fs200 the phone's first screen.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvConfigOverviewShotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    @Test
    @Config(qualifiers = WHOLE_OVERVIEW)
    fun overviewLight() = compose.rvShot("config-overview", dark = false, before = { assertWhole() }) { Overview(RvConfigData.overview) }

    @Test
    @Config(qualifiers = WHOLE_OVERVIEW)
    fun overviewDark() = compose.rvShot("config-overview", dark = true, before = { assertWhole() }) { Overview(RvConfigData.overview) }

    @Test
    fun overviewFs115() = compose.rvShot("config-overview", dark = false, fontScale = RV_FS115) { Overview(RvConfigData.overview) }

    @Test
    fun overviewFs200() = compose.rvShot("config-overview", dark = false, fontScale = RV_FS200) { Overview(RvConfigData.overview) }

    @Test
    @Config(qualifiers = WHOLE_OVERVIEW)
    fun skippedIncludeLight() =
        compose.rvShot("config-overview-skipped-include", dark = false, before = { assertWhole() }) { Overview(RvConfigData.skippedInclude) }

    @Test
    @Config(qualifiers = WHOLE_OVERVIEW)
    fun skippedIncludeDark() =
        compose.rvShot("config-overview-skipped-include", dark = true, before = { assertWhole() }) { Overview(RvConfigData.skippedInclude) }

    @Test
    fun skippedIncludeFs115() = compose.rvShot("config-overview-skipped-include", dark = false, fontScale = RV_FS115) { Overview(RvConfigData.skippedInclude) }

    @Test
    fun skippedIncludeFs200() = compose.rvShot("config-overview-skipped-include", dark = false, fontScale = RV_FS200) { Overview(RvConfigData.skippedInclude) }

    @Test
    fun unsupportedLight() = compose.rvShot("config-unsupported", dark = false, before = { assertWhole() }) { Skipped() }

    @Test
    fun unsupportedDark() = compose.rvShot("config-unsupported", dark = true, before = { assertWhole() }) { Skipped() }

    @Test
    fun unsupportedFs115() = compose.rvShot("config-unsupported", dark = false, before = { assertWhole() }, fontScale = RV_FS115) { Skipped() }

    @Test
    @Config(qualifiers = WHOLE_UNSUPPORTED_FS200)
    fun unsupportedFs200() = compose.rvShot("config-unsupported", dark = false, before = { assertWhole() }, fontScale = RV_FS200) { Skipped() }

    @Test
    fun aFileRowOpensThatFile() {
        var opened = ""
        compose.setContent { Themed { RvConfigOverviewPage(RvConfigData.overview, onBack = {}, onFile = { opened = it }) } }
        compose.onNodeWithText(RvConfigData.LAN).performScrollTo().performClick()
        assertEquals(RvConfigData.LAN, opened)
    }

    @Test
    fun aSkippedRuleOpensItsFileAtItsLine() {
        var opened: RvSkipped? = null
        compose.setContent { Themed { RvConfigSkippedPage(RvConfigData.skippedInclude, onBack = {}, onOpen = { opened = it }) } }
        compose.onNodeWithText(RvConfigData.lanRule.text).performScrollTo().performClick()
        assertEquals(RvConfigData.LAN, opened?.source)
        assertEquals(RvConfigData.lanRule.line, opened?.line)
    }

    @Composable
    private fun Themed(content: @Composable () -> Unit) = RvTheme(seedColorScheme(Seed.Cyan.argb, false), false, content)

    @Composable
    private fun Overview(state: RvConfigState) = RvConfigOverviewPage(state, onBack = {})

    @Composable
    private fun Skipped() = RvConfigSkippedPage(RvConfigData.skippedInclude, onBack = {})

    private companion object {
        const val WHOLE_OVERVIEW = "+h1753dp"
        const val WHOLE_UNSUPPORTED_FS200 = "+h1092dp"
    }
}
