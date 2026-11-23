package dev.doona.android.prototype.rv.config

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.doona.android.prototype.rv.RV_FS115
import dev.doona.android.prototype.rv.RV_FS200
import dev.doona.android.prototype.rv.RvTheme
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
 * The source editor and the diff before 套用 (PV-f item 3), samples only: `rules/cn.dae` checked clean
 * (`proto-rv-config-source-*`) and with an undeclared group after the multibyte `影音` on line 7
 * (`proto-rv-config-source-error-*`); the check's diff (`proto-rv-config-diff-*`) and the same once revision 10 became
 * the head (`proto-rv-config-diff-stale-*`). Light and dark hold the whole page; fs115 and fs200 the first screen.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvConfigSourceShotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    @Test
    @Config(qualifiers = WHOLE_SOURCE)
    fun sourceLight() = compose.rvShot("config-source", dark = false, before = { assertWhole() }) { Source(RvConfigSourceData.source) }

    @Test
    @Config(qualifiers = WHOLE_SOURCE)
    fun sourceDark() = compose.rvShot("config-source", dark = true, before = { assertWhole() }) { Source(RvConfigSourceData.source) }

    @Test
    fun sourceFs115() = compose.rvShot("config-source", dark = false, fontScale = RV_FS115) { Source(RvConfigSourceData.source) }

    @Test
    fun sourceFs200() = compose.rvShot("config-source", dark = false, fontScale = RV_FS200) { Source(RvConfigSourceData.source) }

    @Test
    @Config(qualifiers = WHOLE_ERROR)
    fun errorLight() = compose.rvShot("config-source-error", dark = false, before = { assertWhole() }) { Source(RvConfigSourceData.error) }

    @Test
    @Config(qualifiers = WHOLE_ERROR)
    fun errorDark() = compose.rvShot("config-source-error", dark = true, before = { assertWhole() }) { Source(RvConfigSourceData.error) }

    @Test
    fun errorFs115() = compose.rvShot("config-source-error", dark = false, fontScale = RV_FS115) { Source(RvConfigSourceData.error) }

    @Test
    fun errorFs200() = compose.rvShot("config-source-error", dark = false, fontScale = RV_FS200) { Source(RvConfigSourceData.error) }

    @Test
    @Config(qualifiers = WHOLE_DIFF)
    fun diffLight() = compose.rvShot("config-diff", dark = false, before = { assertWhole() }) { Diff(RvConfigSourceData.diff) }

    @Test
    @Config(qualifiers = WHOLE_DIFF)
    fun diffDark() = compose.rvShot("config-diff", dark = true, before = { assertWhole() }) { Diff(RvConfigSourceData.diff) }

    @Test
    fun diffFs115() = compose.rvShot("config-diff", dark = false, fontScale = RV_FS115) { Diff(RvConfigSourceData.diff) }

    @Test
    fun diffFs200() = compose.rvShot("config-diff", dark = false, fontScale = RV_FS200) { Diff(RvConfigSourceData.diff) }

    @Test
    @Config(qualifiers = WHOLE_STALE)
    fun staleLight() = compose.rvShot("config-diff-stale", dark = false, before = { assertWhole() }) { Diff(RvConfigSourceData.stale) }

    @Test
    @Config(qualifiers = WHOLE_STALE)
    fun staleDark() = compose.rvShot("config-diff-stale", dark = true, before = { assertWhole() }) { Diff(RvConfigSourceData.stale) }

    @Test
    fun staleFs115() = compose.rvShot("config-diff-stale", dark = false, fontScale = RV_FS115) { Diff(RvConfigSourceData.stale) }

    @Test
    fun staleFs200() = compose.rvShot("config-diff-stale", dark = false, fontScale = RV_FS200) { Diff(RvConfigSourceData.stale) }

    @Test
    fun reviewWaitsWhileADiagnosticStands() {
        compose.setContent { Themed { Source(RvConfigSourceData.error) } }
        compose.onNodeWithText(REVIEW).assertIsNotEnabled()
    }

    @Test
    fun aCleanDraftCanBeReviewed() {
        compose.setContent { Themed { Source(RvConfigSourceData.source) } }
        compose.onNodeWithText(REVIEW).assertIsEnabled()
    }

    @Test
    fun aStaleDraftCannotBeApplied() {
        compose.setContent { Themed { Diff(RvConfigSourceData.stale) } }
        compose.onNodeWithText(APPLY).assertIsNotEnabled()
    }

    @Test
    fun aDiffLineOpensItsFileThere() {
        var opened = "" to 0
        compose.setContent { Themed { RvConfigDiffPage(RvConfigSourceData.diff, onBack = {}, onOpen = { file, line -> opened = file to line }) } }
        compose.onNodeWithContentDescription("新增，第 13 行：  domain(suffix: wiki.corp.example) -> direct").performScrollTo().performClick()
        assertEquals(RvConfigSourceData.CN to 13, opened)
    }

    @Composable
    private fun Themed(content: @Composable () -> Unit) = RvTheme(seedColorScheme(Seed.Cyan.argb, false), false, content)

    @Composable
    private fun Source(draft: RvSourceDraft) = RvConfigSourcePage(draft, onBack = {})

    @Composable
    private fun Diff(diff: RvConfigDiff) = RvConfigDiffPage(diff, onBack = {})

    private companion object {
        const val WHOLE_SOURCE = "+h1122dp"
        const val WHOLE_ERROR = "+h1149dp"
        const val WHOLE_DIFF = "+h915dp"
        const val WHOLE_STALE = "+h1030dp"
        const val REVIEW = "檢視變更"
        const val APPLY = "套用"
    }
}
