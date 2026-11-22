package dev.doona.android.prototype.rv.stats

import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.doona.android.prototype.rv.Utc
import dev.doona.android.prototype.rv.assertWhole
import dev.doona.android.prototype.rv.rvShot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Revolut-style 統計 sample, light and dark, in the scheme of the owner's wallpaper, as one long capture of the whole
 * page (goldens `proto-rv-stats-light` and `-dark`): the window is as tall as the page, so nothing scrolls. Both themes
 * show the paged tiles on their first page, 下載 and 上傳, so the two goldens compare (L-04).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h1795dp-notnight-xhdpi")
class RvStatsShotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    @Test
    fun statsLight() = compose.rvShot("stats", dark = false, before = { assertWhole() }) { RvStatsScreen(rvStatsSample(), onBack = {}) }

    @Test
    fun statsDark() = compose.rvShot("stats", dark = true, before = { assertWhole() }) { RvStatsScreen(rvStatsSample(), onBack = {}) }
}
