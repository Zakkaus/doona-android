package dev.doona.android.prototype.rv

import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.doona.android.engine.Mode
import dev.doona.android.engine.Status
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 活動 while it connects (`proto-rv-activity-starting-*`), while it disconnects (`-activity-stopping-*`) and in Direct
 * (`-activity-direct-*`); each light, dark, fs115 and fs200. The two busy states run at animator scale 0, so the 連線
 * action's busy bar rests half full.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvActivityStateShotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    @After
    fun restoreScale() = rvAnimatorScale(1f)

    @Test fun startingLight() = shot("activity-starting", starting(), dark = false)

    @Test fun startingDark() = shot("activity-starting", starting(), dark = true)

    @Test fun startingFs115() = shot("activity-starting", starting(), fontScale = RV_FS115)

    @Test fun startingFs200() = shot("activity-starting", starting(), fontScale = RV_FS200)

    @Test fun stoppingLight() = shot("activity-stopping", stopping(), dark = false)

    @Test fun stoppingDark() = shot("activity-stopping", stopping(), dark = true)

    @Test fun stoppingFs115() = shot("activity-stopping", stopping(), fontScale = RV_FS115)

    @Test fun stoppingFs200() = shot("activity-stopping", stopping(), fontScale = RV_FS200)

    @Test fun directLight() = shot("activity-direct", direct(), dark = false)

    @Test fun directDark() = shot("activity-direct", direct(), dark = true)

    @Test fun directFs115() = shot("activity-direct", direct(), fontScale = RV_FS115)

    @Test fun directFs200() = shot("activity-direct", direct(), fontScale = RV_FS200)

    private fun starting() = rvIdleActivity().copy(status = Status.Starting)

    private fun stopping() = rvIdleActivity().copy(status = Status.Stopping)

    private fun direct() = rvSampleActivity().copy(mode = Mode.Direct)

    private fun shot(name: String, state: RvActivitySampleState, dark: Boolean = false, fontScale: Float = 1f) {
        if (state.status == Status.Starting || state.status == Status.Stopping) rvAnimatorScale(0f)
        compose.rvShot(name, dark, fontScale = fontScale) { RvActivityScreenSample(state, onAction = {}, onConnect = {}) }
    }
}
