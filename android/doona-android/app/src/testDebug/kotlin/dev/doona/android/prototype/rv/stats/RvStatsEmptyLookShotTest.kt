package dev.doona.android.prototype.rv.stats

import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.doona.android.feature.stats.RvStatsEmptyLook
import dev.doona.android.prototype.rv.RV_FS200
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
 * The 統計 page before the first byte of the day, its download and upload tiles in the two earlier forms of
 * [RvStatsEmptyLook]: 活動's tile with no traffic (`proto-rv-stats-empty-v1-*`) and the first statistics sample's
 * (`proto-rv-stats-empty-v2-*`); each light, dark and fs200, in the window that holds the whole page, as
 * `proto-rv-stats-*` does.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h2090dp-notnight-xhdpi")
class RvStatsEmptyLookShotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    @Test fun v1Light() = shot(RvStatsEmptyLook.Activity, dark = false)

    @Test fun v1Dark() = shot(RvStatsEmptyLook.Activity, dark = true)

    @Test fun v1Fs200() = shot(RvStatsEmptyLook.Activity, dark = false, fontScale = RV_FS200)

    @Test fun v2Light() = shot(RvStatsEmptyLook.First, dark = false)

    @Test fun v2Dark() = shot(RvStatsEmptyLook.First, dark = true)

    @Test fun v2Fs200() = shot(RvStatsEmptyLook.First, dark = false, fontScale = RV_FS200)

    private fun shot(look: RvStatsEmptyLook, dark: Boolean, fontScale: Float = 1f) =
        compose.rvShot("stats-empty-v${look.ordinal}", dark, before = { assertWhole() }, fontScale = fontScale) {
            RvStatsScreenSample(rvStatsQuiet(), empty = look)
        }
}
