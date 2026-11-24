package dev.doona.android.prototype.rv

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.doona.android.designsystem.rv.LocalRvTesting
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 活動 while a latency test of every node runs (`proto-rv-activity-testing-*`): a busy bar stands in place of the latency
 * and its change in the 主節點 row; and scrolled to its end (`-activity-testing-scrolled-*`), where 最快節點's rows show
 * theirs. Each light, dark, fs115 and fs200, at animator scale 0, so the bars rest half full. The hero's 測試延遲 action
 * has no busy form and stays as it is.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvActivityTestingShotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    @After
    fun restoreScale() = rvAnimatorScale(1f)

    @Test fun testingLight() = shot("activity-testing", dark = false)

    @Test fun testingDark() = shot("activity-testing", dark = true)

    @Test fun testingFs115() = shot("activity-testing", fontScale = RV_FS115)

    @Test fun testingFs200() = shot("activity-testing", fontScale = RV_FS200)

    @Test fun scrolledLight() = shot("activity-testing-scrolled", dark = false, before = { rvScrollBy(TO_THE_END_DP) })

    @Test fun scrolledDark() = shot("activity-testing-scrolled", dark = true, before = { rvScrollBy(TO_THE_END_DP) })

    @Test fun scrolledFs115() = shot("activity-testing-scrolled", fontScale = RV_FS115, before = { rvScrollBy(TO_THE_END_DP) })

    @Test fun scrolledFs200() = shot("activity-testing-scrolled", fontScale = RV_FS200, before = { rvScrollBy(TO_THE_END_DP) })

    private fun shot(name: String, dark: Boolean = false, fontScale: Float = 1f, before: ComposeContentTestRule.() -> Unit = {}) {
        rvAnimatorScale(0f)
        compose.rvShot(name, dark, before, fontScale) {
            CompositionLocalProvider(LocalRvTesting provides true) { RvActivityScreen(rvSampleActivity(), onAction = {}, onConnect = {}) }
        }
    }

    private companion object {
        /** Further than any page is long: a scroll past the end stops at it. */
        const val TO_THE_END_DP = 4000f
    }
}
